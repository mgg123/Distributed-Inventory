# 高并发抢购场景下系统设计逻辑不自洽分析报告

> 分析日期：2026-04-30
> 分析对象：基于 Redis 分布式强一致库存扣减系统设计文档（spec.md）
> 分析方法：模拟高并发下单抢购场景，按系统设计文档的场景链路逐步推演

---

## 模拟场景设定

**SKU-123** 可售库存 `sq=30000`，秒杀活动开始，目标扣减 TPS = 10000。自动锁库存模块创建 lockOrder-A（lq=10000，16桶），随后滚动创建 lockOrder-B（lq=10000，16桶），此时 inventory 表中 `lq=20000`。

---

## P0 级：致命逻辑矛盾（直接导致超卖）

### 问题1：合并提交 `lq=0` 与多 lockOrder 并存的根本性矛盾

**文档描述**：

- 自动锁库存模块允许同一 SKU 同时存在最多 `max-active=2` 个 ACTIVE 状态的 lockOrder
- 合并提交 SQL：`UPDATE inventory SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}, lq = 0`

**矛盾推演**：

```
T=0s    lockOrder-A 创建 → lq = lq + 10000 = 10000
T=0.5s  lockOrder-B 创建 → lq = lq + 10000 = 20000
        此时 inventory: sq=30000, lq=20000, sq-lq=10000

T=1.0s  lockOrder-A 合并提交：
        UPDATE inventory SET sq = sq - 7000, wq = wq + 7000, lq = 0
        → inventory: sq=23000, wq=7000, lq=0  ← 🔴 lq 被错误重置为 0！

        但 lockOrder-B 仍然 ACTIVE，Redis 分桶中还有 10000 的库存！
        此时 sq - lq = 23000 - 0 = 23000

T=1.0s~ DB 降级扣减路径看到 sq-lq=23000，可以肆意扣减
        但其中 10000 是 lockOrder-B 锁定的，不应被 DB 降级路径访问
        → 🔴 超卖！DB 降级路径侵占了 lockOrder-B 的 Redis 预锁库存
```

**根因**：inventory 表的 `lq` 字段是**所有 lockOrder 的 lq 之和**，但合并提交时 `SET lq = 0` 是全量重置而非减去当前 lockOrder 的锁定量。这本质上是因为 **inventory 表的 lq 字段与 per-lockOrder 分桶设计存在模型不匹配**——lq 是一个聚合值，但合并提交把它当作单一 lockOrder 的独占值来处理。

**修复方向**：合并提交应改为 `SET lq = lq - #{当前lockOrder的lockQuantity}`，而非 `lq = 0`。或者将 lq 拆分为 per-lockOrder 粒度存储。

---

### 问题2：补偿合并提交缺少 `sq` 安全检查，可能导致库存变负

**文档描述**：

> 对孤立 PENDING 明细执行补偿合并：直接 `UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity} WHERE id = #{skuId}`

**矛盾推演**：

```
T=0s    sq=10000, lq=10000 (lockOrder-A)
T=1.0s  lockOrder-A 合并提交：net_deduction=9500
        → sq = 10000 - 9500 = 500, wq = 9500, lq = 0

T=1.01s 补偿扫描发现 3 条孤立 PENDING 明细（合计 800）
        并发执行补偿：
        线程1: sq = 500 - 300 = 200
        线程2: sq = 200 - 300 = -100  ← 🔴 sq 变负！
        线程3: sq = -100 - 200 = -300  ← 🔴 继续恶化！
```

**根因**：补偿合并的 SQL 没有 `WHERE sq >= #{quantity}` 的安全约束，也没有分布式锁保护。在高并发穿透场景下，多条孤立 PENDING 明细可能同时触发补偿，导致 sq 被过度扣减。

**修复方向**：补偿合并也应加 `WHERE sq - lq >= #{quantity}` 约束，且需要按 lockOrderId 维度加分布式锁串行处理。

---

## P1 级：严重逻辑缺陷（高并发下大概率触发少卖/超卖）

### 问题3：锁库存操作缺乏事务保障，Redis 与 DB 可能不一致

**文档描述的核心数据流**：

```
1. Redis: Lua 脚本原子初始化 N 个分桶
2. DB: UPDATE inventory SET lq = lq + #{lockQuantity} WHERE sq - lq >= #{lockQuantity}
3. DB: INSERT lock_inventory_order (status=ACTIVE)
```

**矛盾推演**：

```
场景A：Redis 初始化成功 → DB UPDATE 成功 → INSERT lock_inventory_order 失败
        → lq 已增加，Redis 分桶已创建，但无父单据管理
        → 这些 Redis 分桶永远不会被合并提交清理
        → lq 永远不会被重置，sq-lq 永远偏低
        → 🔴 少卖！可用额度被永久占用

场景B：Redis 初始化成功 → DB UPDATE 成功 → INSERT 成功 → 但应用层返回超时
        → 调用方重试，再次执行锁库存
        → lq 再次增加，Redis 分桶再次创建
        → 🔴 少卖！lq 被重复累加
```

**根因**：步骤 2 和步骤 3 没有在同一个数据库事务中执行，且整个锁库存操作缺乏幂等保障。即使步骤 2 和 3 在同一事务中，Redis 初始化（步骤 1）在事务之外，也无法保证三者的一致性。

**修复方向**：引入锁库存幂等键（如 idempotentKey），DB 操作放在同一事务中，Redis 初始化失败时回滚 DB 事务，DB 事务提交失败时清理 Redis。

---

### 问题4：活跃路由更新时序未定义，可能路由到未完成初始化的 lockOrder

**文档描述**：

> 原子执行 `SET inventory:active_lock:{skuId} = lockOrder-B`

**矛盾推演**：

```
T=0.5s  自动锁库存开始创建 lockOrder-B：
        Step 1: Redis Lua 初始化分桶（耗时 5ms）
        Step 2: DB UPDATE lq（耗时 3ms）
        Step 3: DB INSERT lock_inventory_order（耗时 2ms）
        Step 4: SET active_lock = lockOrder-B  ← 路由更新

        如果 Step 4 在 Step 1~3 完成之前就执行了：
        → 扣减请求路由到 lockOrder-B
        → 查询分桶索引缓存 → 不存在（分桶还没初始化完）
        → 降级走 DB 直接扣减
        → 但此时 lq 可能还没更新，sq-lq 偏高
        → 🔴 潜在超卖风险

        更危险的场景：Step 4 在 Step 2 之前执行
        → 扣减请求走 DB 降级，WHERE sq - lq >= quantity
        → lq 还没增加，sq-lq 偏大
        → DB 降级扣减了本应留给 lockOrder-B 的库存
        → 🔴 超卖！
```

**根因**：文档没有定义路由缓存更新的严格时序——必须在 Redis 分桶初始化完成、DB lq 更新完成、lock_inventory_order 插入完成之后，才能更新路由缓存。

**修复方向**：路由缓存更新必须是锁库存操作的最后一步，且需要与分桶索引缓存的写入保持原子性。

---

### 问题5：PENDING 取消 INCR 回补与合并提交清桶的竞态

**文档描述**：

- PENDING 取消：`Redis INCR 回补 bucket_index 对应的分桶计数`
- 合并提交 Step 5：`清零/删除该 lockOrder 的 Redis 分桶`

**矛盾推演**：

```
T=1.0s  lockOrder-A 合并提交事务已提交（Step 4 完成）
        → 所有 PENDING 明细已标记为 MERGED
        → 但 Step 5（清桶）尚未执行

T=1.001s 极端时序下，一笔在合并提交事务开始前发起的 PENDING 取消
        → 该明细在 Step 4a 之前已被 CANCEL（行锁竞争中 CANCEL 先拿到锁）
        → 执行 Redis INCR 回补（bucket 计数 +1）
        → 此时扣减屏障已生效，但 INCR 使桶计数恢复

T=1.002s 另一个穿透屏障的扣减请求看到桶计数 > 0
        → Lua 脚本扣减成功
        → 插入新的 PENDING 明细（lockOrder-A 已 ARCHIVED）
        → 🔴 又一条孤立 PENDING 明细！

T=1.003s Step 5 执行：清零所有分桶
        → 上面那个新扣减对应的 Redis 计数被清除
        → 但 DB 中有 PENDING 明细记录了这次扣减
        → 补偿机制最终会处理，但期间库存状态不一致
```

**根因**：PENDING 取消的 INCR 回补可能使已被屏障标记的桶计数恢复，允许穿透屏障的请求再次扣减成功，产生更多孤立 PENDING 明细。INCR 回补和清桶操作之间存在竞态窗口。

**修复方向**：合并提交的清桶操作应在事务提交后立即执行，且 INCR 回补前应检查分桶索引是否仍然有效。如果索引已失效，跳过 INCR（因为桶即将被清除）。

---

### 问题6：扣减明细缺乏幂等机制，重试可能导致重复扣减

**文档描述**：

> 关联订单 ID（order_id，必填，用于幂等和回补关联）

**矛盾推演**：

```
用户下单购买 SKU-123 x 1：
T=0s    请求1: Redis 扣减成功 → INSERT deduction_detail → 网络超时，调用方未收到响应
T=1s    请求2（重试）: Redis 扣减成功 → INSERT deduction_detail → 成功
        → 🔴 同一订单扣减了两次！Redis 计数器被扣了 2 次，DB 有 2 条明细

合并提交时：net_deduction 包含了 2 条明细
→ sq 被多扣 1 件 → 🔴 少卖（对商家而言）
→ 用户只买了 1 件但库存扣了 2 件
```

**根因**：文档仅提到 order_id "用于幂等"，但没有定义具体的幂等实现机制——是唯一索引？是先查后插？是分布式锁？在高并发重试场景下，如果没有 `(order_id, sku_id)` 的唯一索引约束，重复扣减几乎必然发生。

**修复方向**：deduction_detail 表应建立 `(order_id, sku_id)` 唯一索引，插入前先查询或使用 INSERT ON DUPLICATE KEY 机制。

---

## P2 级：设计缺陷（特定场景下影响可用性或准确性）

### 问题7：Redis 全锁定 + Redis 不可用 = 完全服务降级

**矛盾推演**：

```
sq=10000, lq=10000 (全部库存锁定到 Redis)
→ sq - lq = 0

此时 Redis 集群故障：
→ Redis 分桶扣减：失败
→ DB 降级扣减：WHERE sq - lq >= quantity → 0 >= 1 → 失败
→ 🔴 所有扣减请求均失败！完全不可用！

即使 Redis 部分可用（网络分区）：
→ 扣减超时 → 当作失败 → 走 DB 降级 → sq-lq=0 → 失败
→ 🔴 仍然完全不可用！
```

**根因**：DB 降级路径的 WHERE 条件 `sq - lq >= #{quantity}` 将锁定部分排除在外，当全部库存被锁定到 Redis 时，DB 降级路径无法访问任何库存。这是"Redis 仅作计数器"设计原则的副作用——lq 占用了 sq 的可用额度，但 Redis 不可用时这部分额度无法被释放。

**修复方向**：Redis 不可用时，应触发紧急合并提交（将当前 lockOrder 的 lq 释放），或者提供"紧急解锁"接口将 lq 重置为 0，使 DB 降级路径可用。

---

### 问题8：合并提交 `sq` 扣减无 WHERE 安全约束

**文档描述**：

```sql
UPDATE inventory SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}, lq = 0
WHERE id = #{skuId}
```

**矛盾推演**：

虽然正常情况下 `net_deduction <= lq`（Redis Lua 脚本防止超扣），且 DB 降级只能访问 `sq - lq` 部分，理论上 `sq >= lq >= net_deduction`。但结合问题1（lq=0 错误重置），这个安全假设被打破：

```
lockOrder-A 合并后 lq=0（错误），lockOrder-B 仍 ACTIVE
→ DB 降级路径看到 sq-lq 偏大，大量扣减
→ lockOrder-B 合并时 net_deduction 可能超过剩余 sq
→ UPDATE 无 WHERE sq >= net_deduction 约束
→ 🔴 sq 变负！
```

**修复方向**：合并提交 SQL 应增加 `WHERE sq >= #{net_deduction}` 或 `WHERE sq - lq + #{当前lockOrder的lockQuantity} >= #{net_deduction}` 作为最终防线。

---

### 问题9：自动锁库存的"分桶余量阈值"检测不精确

**文档描述**：

> 当前活跃 lockOrder 的分桶总余量低于阈值（默认 50%）时，提前创建下一个 lockOrder

**矛盾推演**：

```
16 个分桶，每桶 625，总余量 = 10000
高并发下检测余量：
  GET bucket:0 → 600
  GET bucket:1 → 580
  ...（16 次 GET 请求，耗时约 2ms）
  GET bucket:15 → 300
  计算总余量 = 5800 → 低于 50%，触发自动锁库存

但在检测过程中：
  T+0ms: bucket:0 = 600
  T+0.5ms: bucket:0 被扣减到 400（高并发下变化极快）
  T+2ms: 检测完成，但数据已过时

  可能的误判：
  - 实际余量已耗尽，但检测时还有 50%→ 延迟创建新 lockOrder
  - 实际余量还剩 60%，但检测时恰好低谷→ 过早创建
```

**根因**：分桶余量是分散在 N 个 Redis Key 中的，无法原子读取所有桶的值。在高 TPS 下，检测过程中的值不断变化，检测结果天然不精确。

**修复方向**：维护一个独立的 Redis Key 记录总余量（每次扣减/回补时同步更新），或使用 Redis Lua 脚本原子读取所有桶值求和。

---

### 问题10：合并提交后 Redis 清桶与应用崩溃的恢复问题

**矛盾推演**：

```
合并提交流程：
Step 4: @Transactional 事务提交成功 → sq/wq/lq 已更新，lockOrder 已 ARCHIVED
Step 5: 清零/删除 Redis 分桶  ← 应用在此处崩溃！

重启后：
→ lockOrder-A 状态 = ARCHIVED（DB 已持久化）
→ Redis 分桶仍然存在，计数器可能 > 0
→ 但扣减屏障（meta 缓存）可能在崩溃中丢失
→ 如果 meta 缓存也被清除，扣减请求可能路由到这些残留分桶
→ 🔴 对已 ARCHIVED 的 lockOrder 产生新的扣减
```

**根因**：Step 4（DB 事务）和 Step 5（Redis 操作）不是原子操作。DB 事务提交后 Redis 操作失败，系统没有自动恢复机制。

**修复方向**：合并提交完成后，在 lock_inventory_order 表增加 `merge_completed` 标记位。启动时扫描 `status=ARCHIVED AND merge_completed=false` 的记录，补偿清理 Redis 分桶。

---

### 问题11：锁库存不支持部分锁定，可用额度不足时全有或全无

**文档描述**：

> 锁定量决策：新 lockOrder 的 lockQuantity = `store.auto-lock.quantity`（默认 10000）

**矛盾推演**：

```
sq=15000, lq=10000 (lockOrder-A)
→ sq - lq = 5000

自动锁库存尝试创建 lockOrder-B，lockQuantity=10000
→ WHERE sq - lq >= 10000 → 5000 >= 10000 → 失败！
→ 返回 LOCK_QUANTITY_EXCEEDED

但实际上还有 5000 可用额度！这 5000 无法被锁定到 Redis
→ 只能走 DB 降级路径
→ 🔴 5000 件库存无法享受 Redis 高 TPS 扣减，降级到 DB 性能
```

**根因**：锁库存是全量锁定（lockQuantity 是固定配置值），不支持部分锁定。当剩余可用额度小于 lockQuantity 时，无法创建新的 lockOrder。

**修复方向**：支持部分锁定——当 `sq - lq < lockQuantity` 时，自动调整为 `sq - lq` 作为实际锁定量。

---

## P3 级：设计模糊点（需要明确才能正确实现）

### 问题12：自动锁库存的"连锁触发"机制未定义触发方式

文档说"后续下单扣减过程中，则会继续自动触发锁库存"，但未定义：

- 是在每次扣减请求中同步检测？（增加扣减延迟）
- 是后台定时任务检测？（有延迟，可能错过窗口）
- 是基于 Redis Keyspace Notification？（复杂度高）

### 问题13：合并提交的 `merge_batch_id` 与补偿合并的交互未定义

补偿合并会"填充 merge_batch_id 标记为补偿合并"，但：

- 补偿 merge_batch_id 的生成规则未定义
- 是否与正常合并的 batch_id 在同一命名空间？
- 补偿合并是否需要独立的分布式锁？

### 问题14：历史路由兜底的性能与正确性权衡

文档说"按创建时间倒序遍历，检查每个 lockOrder 的分桶索引是否有效"，但：

- 旧 lockOrder 可能余量极少，路由过去后频繁 fallover
- 遍历多个 lockOrder 增加扣减延迟
- 未定义遍历超时机制

---

## 问题汇总矩阵

| 编号 | 严重度 | 问题 | 后果 | 影响链路阶段 |
|------|--------|------|------|-------------|
| 1 | P0 | 合并提交 lq=0 与多 lockOrder 并存矛盾 | 超卖 | 合并提交 |
| 2 | P0 | 补偿合并缺少 sq 安全检查 | sq 变负/超卖 | 补偿机制 |
| 3 | P1 | 锁库存操作缺乏事务保障 | 少卖/额度永久占用 | 锁库存 |
| 4 | P1 | 活跃路由更新时序未定义 | 超卖 | 自动锁库存+路由 |
| 5 | P1 | PENDING 取消 INCR 与清桶竞态 | 孤立明细/状态不一致 | 合并提交+取消 |
| 6 | P1 | 扣减明细缺乏幂等机制 | 重复扣减/少卖 | 下单扣减 |
| 7 | P2 | Redis 全锁定+不可用=完全降级 | 服务不可用 | 降级路径 |
| 8 | P2 | 合并提交 sq 扣减无 WHERE 约束 | sq 变负（依赖问题1触发） | 合并提交 |
| 9 | P2 | 分桶余量阈值检测不精确 | 自动锁库存时机不准 | 自动锁库存 |
| 10 | P2 | 清桶与崩溃恢复 | Redis 残留分桶 | 合并提交 |
| 11 | P2 | 不支持部分锁定 | 可用额度浪费 | 锁库存 |
| 12 | P3 | 连锁触发机制未定义 | 实现歧义 | 自动锁库存 |
| 13 | P3 | 补偿 merge_batch_id 交互未定义 | 实现歧义 | 补偿机制 |
| 14 | P3 | 历史路由兜底性能权衡 | 实现歧义 | 路由机制 |

---

## 核心矛盾总结

所有问题的根因可以归结为 **一个核心架构矛盾**：

> **inventory 表的 lq 字段是所有 lockOrder 的聚合值，但系统的 per-lockOrder 分桶设计要求每个 lockOrder 独立管理自己的生命周期。**

这个矛盾在单 lockOrder 场景下不会暴露（lq 就等于当前 lockOrder 的锁定量，合并后重置为 0 是正确的），但在多 lockOrder 并存场景下（自动锁库存模块明确支持），lq 的语义从"当前锁定量"变成了"所有活跃锁定量之和"，而合并提交仍然按"当前锁定量"来处理，导致一系列连锁问题。

**建议的架构修正方向**：

1. **方案A：lq 减量更新**——将合并提交 SQL 改为 `SET lq = lq - #{当前lockOrder的lockQuantity}`，而非 `lq = 0`。需要确保 lockQuantity 在 lock_inventory_order 表中可靠记录。
2. **方案B：lq 查询替代**——彻底移除 inventory 表的 lq 字段，通过实时查询 `SELECT SUM(lock_quantity) FROM lock_inventory_order WHERE sku_id = #{skuId} AND status = 'ACTIVE'` 来计算有效锁定量。DB 降级扣减的 WHERE 条件改为 `sq - (SELECT COALESCE(SUM(lock_quantity), 0) FROM lock_inventory_order WHERE sku_id = #{skuId} AND status = 'ACTIVE') >= #{quantity}`。
3. **方案C：lq 拆分存储**——在 lock_inventory_order 表中维护每个 lockOrder 的 lockQuantity，inventory 表的 lq 仅作为缓存冗余字段，合并提交时从 lock_inventory_order 的 SUM 值重新计算 lq。
