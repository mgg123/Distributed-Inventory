# 基于Redis分布式强一致库存扣减系统 Spec Review 报告

> Review 日期：2026-04-30
> Review 对象：`/Users/edy/work/project/Distributed-Inventory/store/.trae/specs/redis-inventory-deduction/spec.md`
> Review 重点：库存扣减/回收链路在高并发场景下的超卖/少卖风险
> 参考文献：https://mp.weixin.qq.com/s/_ezTVydFszZnc0ZN-JEtlQ

---

## 总体评价

spec 文档在整体架构设计上是合理的——Redis仅做计数器、DB明细为真相源、扣减屏障防并发冲突等思路与参考文献一致且正确。但在**关键链路的并发安全细节**上存在若干问题，其中3个为严重级别，可能导致超卖或少卖。

---

## 🔴 严重问题（可能导致超卖/少卖）

### 问题1：DB降级扣减SQL未考虑lq字段 — 超卖风险

**位置**：spec.md 降级DB扣减路径（Scenario: Redis异常降级）

**现状**：DB降级扣减使用的SQL为：

```sql
UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity}
WHERE id = #{skuId} AND sq >= #{quantity}
```

**问题**：`WHERE sq >= #{quantity}` 只检查了sq总量，**没有扣除lq已锁定的部分**。当lq > 0时，DB直接扣减可以蚕食lq锁定的库存，导致合并提交后sq变负。

**超卖推演**：

```
初始状态: sq=1000, lq=0, wq=0
锁库存:   sq=1000, lq=800, wq=0   (Redis分桶初始化800)
Redis扣减: 500件成功 (PENDING明细500件)
DB降级扣减: 600件 → WHERE sq >= 600 → 成功! (sq=1000 >= 600)
           sq=400, lq=800, wq=600
合并提交:  净扣减=500
           sq = 400 - 500 = -100 ← 负数！超卖100件！
```

**修复建议**：DB降级扣减SQL应改为：

```sql
UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity}
WHERE id = #{skuId} AND sq - lq >= #{quantity}
```

这与锁库存操作的约束条件保持一致，确保DB直接扣减不会侵占lq锁定的库存。

---

### 问题2：合并提交"扫描-计算-更新"非原子 — 超卖+少卖风险

**位置**：spec.md 合并流程伪代码 Step 4-6

**现状**：合并提交的流程是：

1. 扫描PENDING明细（Step 4）
2. 计算净扣减值（Step 5）
3. 事务内更新DB（Step 6）

Step 4 和 Step 6 之间存在时间窗口，高并发下会出现两类竞态：

#### 竞态A：新PENDING明细在扫描后插入 → 超卖

```
T1: 合并扫描 → 发现100条PENDING明细，总扣减500件
T2: 并发扣减请求 → Lua脚本成功 → 插入PENDING明细(10件)
T3: 合并事务 → sq -= 500, wq += 500
              → UPDATE status='MERGED' WHERE status='PENDING'
              → T2插入的明细也被标记为MERGED（因为满足WHERE条件）
结果: sq只减了500，但实际MERGED了510件 → sq偏高10件 → 后续操作可能超卖
```

#### 竞态B：PENDING明细在扫描后被取消 → 少卖

```
T1: 合并扫描 → 发现100条PENDING明细，总扣减500件
T2: 用户取消订单 → PENDING明细状态改为CANCELLED + Redis INCR回补
T3: 合并事务 → sq -= 500 (基于扫描结果)
              → UPDATE status='MERGED' WHERE status='PENDING'
              → T2的明细已是CANCELLED，不会被更新
结果: sq多减了被取消的数量 → sq偏低 → 少卖
```

**修复建议**：

方案一（推荐）：**将扫描纳入事务，使用锁定读**

```sql
SELECT SUM(quantity) FROM deduction_detail
WHERE status='PENDING' AND merge_batch_id IS NULL AND lock_order_id = #{lockOrderId}
FOR UPDATE;  -- 锁定这些行，阻止并发修改状态
```

方案二：**事务内重新计算净扣减值**，不在事务外预计算：

```
@Transactional
  1. SELECT ... FOR UPDATE 锁定PENDING明细
  2. 计算净扣减值（此时已持有行锁，不会被并发修改）
  3. UPDATE inventory / deduction_detail / lock_inventory_order
```

---

### 问题3：deduction_detail缺少桶标识字段 — INCR回补不可靠

**位置**：spec.md 扣减明细模型字段定义

**现状**：deduction_detail 模型没有记录扣减发生在哪个具体的Redis桶。但spec在多处要求"INCR回补对应分桶计数"：

- PENDING取消时：`INCR回补对应分桶计数`
- 明细插入失败时：`INCR恢复对应分桶计数`

**问题**：不知道回补哪个桶，INCR操作无法精确执行。如果回补到错误的桶：

- 回补到仍有余量的桶 → 该桶余量偏高 → 可能导致该桶多卖 → **超卖**
- 回补到已耗尽的桶 → 该桶余量恢复 → 其他桶余量偏低 → **少卖**（其他桶提前耗尽）

**修复建议**：在 deduction_detail 中增加 `bucket_index` 字段，记录扣减发生的具体桶编号：

```
- bucket_index（整数，MERGE_BUCKETS路径必填，DIRECT_DB路径为NULL）
```

扣减时记录桶编号，回补时精确INCR对应桶。

---

## 🟡 中等问题（可能导致少卖或性能退化）

### 问题4：扣减屏障存在"已读索引"穿透窗口 — 潜在超卖

**位置**：spec.md 一致性保障机制 - 扣减屏障防并发冲突

**现状**：扣减屏障通过"失效分桶索引缓存"实现，后续请求检查索引有效性来感知屏障。但存在穿透窗口：

```
T1: 扣减请求读取分桶索引缓存 → 获取桶列表（索引仍有效）
T2: 合并提交失效分桶索引缓存
T3: 扣减请求用T1获取的桶列表执行Lua脚本 → 成功！（桶数据还在）
T4: 扣减请求插入PENDING明细
T5: 合并提交扫描PENDING明细 → 未包含T4的明细
T6: 合并提交事务 → T4的明细被意外标记MERGED但未计入净扣减
```

这与问题2的竞态A是同一根因，但值得单独指出：**扣减屏障只能阻止"尚未读取索引"的新请求，无法阻止"已读取索引但尚未执行Lua"的进行中请求**。

**修复建议**：在合并提交的事务内，除了更新PENDING明细状态外，还应**重新计算净扣减值**（而非使用事务外预计算的值），确保事务内计算的值与实际标记MERGED的明细一致。

---

### 问题5：活跃路由只指向一个lockOrder — Redis利用率不足

**位置**：spec.md 活跃lockOrder路由机制 - 路由数据结构

**现状**：`inventory:active_lock:{skuId}` 只存储一个lockOrderId。当滚动创建新lockOrder时，路由切换到新的，旧的lockOrder虽然仍ACTIVE但不再被新请求路由到。

**问题**：旧lockOrder的剩余Redis库存无法被利用，导致：

- 旧lockOrder桶内剩余库存浪费（直到合并提交才回收）
- 更多流量被迫降级到DB路径
- 极端情况下，新lockOrder的桶已耗尽但旧lockOrder还有余量，请求却降级到DB → **少卖**（DB路径受sq-lq约束更严格）

**修复建议**：路由缓存改为有序列表，扣减时依次尝试：

```
Redis Key: inventory:active_lock:{skuId}
Value: List[lockOrderId]（按创建时间排序，最新的在前）

扣减逻辑：
1. 遍历活跃lockOrder列表
2. 检查每个lockOrder的分桶索引是否有效
3. 第一个有效的lockOrder用于扣减
4. 全部无效则降级DB
```

---

### 问题6：PENDING取消的Redis INCR与合并提交的竞态 — 少卖

**位置**：spec.md PENDING状态取消场景

**现状**：PENDING取消时需要INCR回补Redis分桶，但INCR操作与合并提交的桶清除操作存在竞态：

```
T1: 用户取消PENDING明细 → INCR回补桶计数（桶计数+10）
T2: 合并提交 → 清零/删除该lockOrder的Redis分桶
结果: T1的INCR回补被T2的清零操作覆盖 → 回补丢失 → 少卖
```

**修复建议**：PENDING取消时，除了Redis INCR回补外，还应**在DB层面记录回补信息**（如在deduction_detail上标记cancel_time和cancel_quantity），合并提交时通过扫描CANCELLED明细来修正净扣减值，而非完全依赖Redis INCR。

---

## 🟢 轻微问题（数据一致性/可靠性风险）

### 问题7：合并提交后可能产生孤立的PENDING明细

**位置**：合并提交流程 Step 6-7 之间

如果扣减请求在合并提交事务提交后、Redis桶清除前完成了Lua脚本和明细插入，会产生一个PENDING明细，但其父lockOrder已ARCHIVED。该明细永远无法合并，用户订单卡在PENDING状态。

**修复建议**：合并提交完成后，增加一个短暂的"冷却期"再清除Redis桶，或在合并提交后扫描是否有新的孤立PENDING明细并进行补偿处理。

---

### 问题8：锁库存"先Redis后DB"策略的Redis部分初始化失败

**位置**：spec.md 一致性保障机制 - 异常场景处理

**现状**：spec提到"先Redis后DB策略降低失败概率"，但未详细说明Redis部分桶初始化成功的处理。如果16个桶中只有10个初始化成功，lq=10000但Redis只有6250可用，会导致少卖。

**修复建议**：Redis桶初始化应使用Pipeline或Lua脚本保证原子性——要么全部成功，要么全部回滚。如果部分失败，清理已初始化的桶后重试。

---

## 📊 问题汇总

| # | 严重度 | 问题 | 风险类型 | 根因 |
|---|--------|------|----------|------|
| 1 | 🔴严重 | DB降级扣减SQL未考虑lq | **超卖** | WHERE条件缺少lq约束 |
| 2 | 🔴严重 | 合并提交扫描-计算-更新非原子 | **超卖+少卖** | 事务外预计算与事务内更新不一致 |
| 3 | 🔴严重 | deduction_detail缺少桶标识 | **超卖/少卖** | INCR回补无法精确到桶 |
| 4 | 🟡中等 | 扣减屏障"已读索引"穿透 | **超卖** | 屏障无法阻止进行中请求 |
| 5 | 🟡中等 | 活跃路由只指向一个lockOrder | **少卖** | 旧lockOrder库存无法被路由 |
| 6 | 🟡中等 | PENDING取消INCR与合并清桶竞态 | **少卖** | INCR回补被清桶操作覆盖 |
| 7 | 🟢轻微 | 合并后孤立PENDING明细 | 数据不一致 | 合并事务与桶清除的时间窗口 |
| 8 | 🟢轻微 | Redis桶部分初始化失败 | **少卖** | 缺少原子初始化保障 |

---

## 核心建议

1. **最优先修复问题1**：DB降级扣减SQL加上 `sq - lq >= #{quantity}` 约束，这是最直接的超卖漏洞
2. **重构合并提交流程（问题2+4）**：将扫描和计算移入事务内，使用 `SELECT ... FOR UPDATE` 锁定行，确保计算与更新的原子性
3. **增加桶标识字段（问题3）**：在deduction_detail中增加bucket_index，使INCR回补精确可靠
4. **PENDING取消的双保险（问题6）**：Redis INCR + DB记录取消信息，合并提交时交叉验证

这三个严重问题互相关联：问题2的竞态窗口中，如果INCR回补不精确（问题3），会放大不一致性；而问题1的超卖路径在DB降级场景下是确定性的，不依赖竞态条件。建议按 **1→2→3** 的顺序修复。
