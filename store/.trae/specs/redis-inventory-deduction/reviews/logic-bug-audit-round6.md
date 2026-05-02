# 基于Redis分布式强一致库存扣减系统 — 第六轮全方位逻辑Bug纠察报告

> 纠察日期：2026-05-02
> 纠察对象：经第五轮评审修复后的 `spec.md`（约1300行）
> 纠察方法：逐模块逐场景推演高并发竞态时序，交叉验证文档内部一致性（伪代码 vs 场景描述 vs 核心数据流 vs 约束条件），识别逻辑BUG/内部不一致/实现遗漏
> 前置参考：system-design-review-round5.md 及其19个问题的修复

---

## 一、第五轮修复验证

第五轮评审识别的19个问题已全部修复并验证通过：

| 修复项 | 严重度 | 验证结果 |
|--------|--------|---------|
| 合并提交SQL增加 `lq >= #{currentLockQuantity}` 约束 | P0 | ✅ 三处SQL + 约束层级定义均已统一 |
| 并发控制策略公式统一 reserve-ratio | P0 | ✅ `min(lockQuantity, (sq-lq)*(1-reserveRatio))` 全文一致 |
| 锁库存Step3失败补偿机制 | P1 | ✅ 重试+后台补偿+崩溃恢复三重保障 |
| Lua扣减脚本增加返回值2+total_remaining检查 | P1 | ✅ 脚本+扣减流程+核心数据流均已更新 |
| refund_detail业务级幂等约束 | P1 | ✅ uk_ref_detail_request唯一索引+MySQL NULL行为说明 |
| Redis Cluster Hash Tag兼容 | P1 | ✅ Key格式映射表+全文逻辑Key格式统一 |
| Step 4b SUM NULL处理 | P1 | ✅ COALESCE(SUM, 0) 全文统一 |
| Step 0 ARCHIVED状态检查 | P2 | ✅ SELECT id, status + LOCK_ORDER_ALREADY_ARCHIVED |
| QPS测量机制定义 | P2 | ✅ 滑动窗口计数器方案 |
| Redis超时统计维度 | P2 | ✅ 实例级AtomicInteger方案 |
| reserve-ratio与min-lock-quantity死区 | P2 | ✅ 文档明确标注设计权衡 |
| 同步快检事件去重 | P2 | ✅ SETNX + TTL方案 |
| 定时任务活跃lockOrder列表获取 | P2 | ✅ 路由缓存优先+DB定期补充 |
| Lua INCR回补脚本去重 | P3 | ✅ 删除重复定义，改为引用 |
| lq减量更新解释去重 | P3 | ✅ 权威定义+引用 |
| 路由缓存主动清理 | P3 | ✅ 合并后主动删除/更新 |
| ARCHIVED记录归档 | P3 | ✅ 归档表/分区表方案 |
| bucket_index有效性校验 | P3 | ✅ 应用层校验+告警 |

---

## 二、第六轮纠察新发现的问题

在第五轮修复基础上，通过逐模块推演和交叉验证，又发现 **8个问题**，其中1个P0级、3个P1级、2个P2级、2个P3级。

### 🔴 P0 级：严重逻辑BUG

#### 问题 A：合并流程伪代码 Step 4a 影响0行时未跳过 → 二次合并触发导致lq变负

**位置**：spec.md → 合并流程伪代码 Step 4a.5（已修复）

**原始问题**：

合并流程伪代码中，Step 4a（UPDATE PENDING→MERGED）可能影响0行（无待合并明细，如二次触发合并）。原伪代码未在此处跳过，继续执行 Step 4c（读取lock_quantity）和 Step 4d（lq减量更新），导致：

```
首次合并: lq = lq - 10000 → lq正确减少
二次合并: Step 4a影响0行 → Step 4c读取lock_quantity=10000 → Step 4d: lq = lq - 10000
→ WHERE lq >= 10000 → 失败！事务回滚，触发告警
→ 虽然不会导致数据不一致（事务回滚保护），但产生大量虚假告警
```

**修复状态**：✅ 已修复。增加 Step 4a.5：IF 影响行数为0，直接跳过Step 4b-4e，释放分布式锁，返回。核心数据流同步更新。

---

### 🟠 P1 级：严重逻辑缺陷/内部不一致

#### 问题 B：核心数据流 Step 0 缺少 status 检查 — 与 Step 0 定义不一致

**位置**：spec.md → 核心数据流 Step 1（预热阶段） vs 锁库存操作严格时序 Step 0

**原始不一致**：

- Step 0 定义：`SELECT id, status FROM lock_inventory_order WHERE idempotent_key = #{idempotentKey}`，区分ACTIVE/ARCHIVED
- 核心数据流：`SELECT id FROM lock_inventory_order WHERE idempotent_key = #{idempotentKey}`，不检查status

**影响**：实现者按核心数据流编码，可能返回ARCHIVED的lockOrderId，导致扣减请求路由到已失效的lockOrder。

**修复状态**：✅ 已修复。核心数据流Step 0统一为 `SELECT id, status`，增加ARCHIVED状态处理。

---

#### 问题 C：扣减流程未检查紧急降级开关 — 紧急降级期间可能产生数据不一致

**位置**：spec.md → 核心数据流 Step 2（下单阶段） vs 紧急降级方案

**原始问题**：

紧急降级方案定义了全局降级开关 `inventory:emergency_degrade:{skuId}`（TTL=30s），用于在Redis分桶批量清零期间暂停Redis路径扣减。但扣减流程（核心数据流Step 2）未检查此开关。

**矛盾推演**：

```
T=0s  管理员触发紧急解锁，选择快速释放路径：
      1. Lua脚本批量清零所有ACTIVE lockOrder的Redis分桶
      2. SET inventory:emergency_degrade:{skuId} = true, TTL=30s
      3. SET lq = 0

T=0.5s  扣减请求到达：
      → 未检查emergency_degrade开关
      → 查询路由缓存 → 获取lockOrderId
      → 检查分桶索引 → 已被清零，不存在
      → 降级走DB路径：WHERE sq - lq >= 1 → sq - 0 >= 1 → 成功

T=1s  另一个扣减请求到达：
      → 同样走DB路径成功

      问题：Redis分桶清零后，原本在Redis中的库存计数已丢失
      → 这些库存已被DB降级路径重新扣减
      → 但如果Redis部分恢复，路由缓存仍指向旧lockOrderId
      → 扣减请求可能尝试走Redis路径，发现分桶为空，降级走DB
      → 实际上不会超卖（因为lq=0，sq-lq=sq，DB降级路径有足够额度）
      → 但可能少卖（Redis分桶清零后，原本预扣减的库存计数丢失，
         已在Redis中扣减但尚未合并提交的PENDING明细无法通过Redis回补）
```

**修复状态**：✅ 已修复。核心数据流增加"紧急降级开关检查"步骤，开关存在时直接走DB降级路径。

---

#### 问题 D：紧急SET lq=0后未同步更新lockOrder状态 — 系统不一致

**位置**：spec.md → 紧急降级方案

**原始问题**：

紧急降级方案允许在极端情况下 `SET lq = 0`（先清零Redis分桶+设置降级开关），但未提及需要同步更新 `lock_inventory_order` 的状态为ARCHIVED。

**影响**：

```
SET lq = 0 后：
  - inventory.lq = 0
  - lock_inventory_order 仍有 ACTIVE 状态的记录
  - 自动锁库存检查 sq - lq = sq - 0 = sq（全部可用）
  - 自动锁库存创建新lockOrder → lq增加
  - 但旧的ACTIVE lockOrder仍存在，占用max-active名额
  - 可能导致无法创建新lockOrder（max-active=2，但2个都是"僵尸"ACTIVE）
```

**修复状态**：✅ 已修复。紧急降级方案增加"SET lq=0后必须同步更新所有ACTIVE lockOrder状态为ARCHIVED"。

---

### 🟡 P2 级：文档内部不一致

#### 问题 E：核心数据流合并步骤锁释放时机与伪代码不一致

**位置**：spec.md → 核心数据流 Step 3（合并阶段） vs 合并流程伪代码 Step 4.5

**原始不一致**：

- 伪代码 Step 4.5：在Step 4事务完成后、Step 5-6之前释放分布式锁（提前释放，因为Step 5-6是幂等操作）
- 核心数据流：分布式锁释放位于最后一步（Step 5-6之后）

**影响**：实现者按核心数据流编码，锁持有时间更长，不同lockOrder的合并操作串行化程度更高，但不影响正确性。

**修复状态**：✅ 已修复。核心数据流合并步骤的锁释放位置调整到事务完成后、Redis清理之前，与伪代码一致。

---

#### 问题 F：补偿场景描述缺少 COALESCE — 与核心数据流不一致

**位置**：spec.md → 合并提交后孤立PENDING明细补偿 Scenario vs 核心数据流 Step 6

**原始不一致**：

- 核心数据流 Step 6：`SELECT COALESCE(SUM(quantity), 0)`
- 补偿场景描述：`SELECT SUM(quantity) AS net_deduction`（缺少COALESCE）

**修复状态**：✅ 已修复。补偿场景描述统一使用COALESCE。

---

### 🟢 P3 级：文档规范/优化建议

#### 问题 G：Key格式使用不一致 — Step 3和QPS Key曾使用Hash Tag格式

**位置**：spec.md → 多处Key引用

**原始问题**：

文档约定"为可读性使用逻辑Key格式"，但部分位置（Step 3定义、QPS Key）曾直接使用Hash Tag格式（如 `inventory:{skuId}:active_lock`），与约定不一致。

**修复状态**：✅ 已修复。全文统一使用逻辑Key格式，Hash Tag映射表作为唯一实现参考。QPS Key、路由Key等新增Key已补充到映射表。

---

#### 问题 H：refund_request_id NULL 在 MySQL InnoDB 唯一索引中的行为说明不充分

**位置**：spec.md → refund_detail 索引定义

**原始问题**：

文档说"refund_request_id为NULL时不参与唯一索引约束"，但未说明MySQL InnoDB的具体行为：NULL值在唯一索引中不被视为相等，因此 `(ref_detail_id=1, refund_request_id=NULL)` 可以存在多行，唯一索引在refund_request_id为NULL时不提供任何去重保护。

**修复状态**：✅ 已修复。增加MySQL InnoDB NULL行为说明，建议调用方始终传入refund_request_id。

---

## 三、修复后最终推演验证

对修复后的spec.md进行全链路推演，验证核心场景的正确性：

### 场景1：正常扣减链路 ✅

```
锁库存 → Redis分桶初始化 → 路由缓存更新 → 扣减请求路由 → Lua扣减 → DB明细插入 → 合并提交 → lq减量更新
```

所有步骤时序正确，幂等保障完备。

### 场景2：高并发扣减 + 自动锁库存滚动 ✅

```
Lock-A余量低 → 同步快检触发事件（SETNX去重） → Lock-B创建 → 路由原子切换 → Lock-A合并提交 → Lock-B继续服务
```

滚动管线无空窗期，事件去重避免重复触发。

### 场景3：合并提交与PENDING取消竞态 ✅

```
合并提交Step 4a获取行锁 → CANCEL被阻塞 → 合并完成后CANCEL走MERGED取消路径
或：CANCEL先完成 → PENDING变CANCELLED + INCR回补 → 合并提交Step 4a不包含已取消明细
```

先标记后计算+行锁保障正确性。

### 场景4：二次合并触发（幂等） ✅

```
首次合并: Step 4a标记PENDING→MERGED → Step 4d lq减量 → Step 4e ARCHIVED
二次合并: Step 4a影响0行 → Step 4a.5跳过 → 释放锁返回
```

Step 4a.5是关键的幂等保障，避免lq变负。

### 场景5：紧急降级 ✅

```
Redis不可用 → 连续超时5次 → 自动触发紧急合并提交
或：管理员触发emergencyUnlock → 逐个合并提交 → lq释放 → DB降级路径可用
快速释放路径：清零Redis + SET lq=0 + SET emergency_degrade + UPDATE lockOrder→ARCHIVED
扣减请求：检查emergency_degrade → 跳过Redis → 走DB降级
```

紧急降级开关检查是关键修复点，防止降级期间数据不一致。

### 场景6：Redis Cluster部署 ✅

```
所有Key使用Hash Tag → 同一实体的Key在同一slot → Lua脚本可正常执行
```

Key格式映射表完整覆盖所有Key类型。

### 场景7：分桶耗尽触发合并 ✅

```
Lua扣减 → total_remaining减至0 → 返回2 → 应用层异步触发合并提交
```

返回值2已在Lua脚本和扣减流程中统一实现。

### 场景8：锁库存Step 3失败 ✅

```
Step 3失败 → 重试3次 → 仍失败 → 后台补偿任务扫描5秒前ACTIVE但无路由的lockOrder → 补偿更新路由缓存
```

三重保障（重试+补偿+崩溃恢复）确保路由不丢失。

---

## 四、当前版本残余风险（非BUG，为设计权衡）

经过六轮评审和修复，当前spec.md已无已知的逻辑BUG。以下为已知的设计权衡，不影响正确性但需实现者注意：

| 风险项 | 说明 | 影响 | 缓解措施 |
|--------|------|------|---------|
| reserve-ratio与min-lock-quantity死区 | 约11件可用额度无法被Redis锁定 | 极轻微少卖 | 可调低min-lock-quantity或reserve-ratio |
| 合并窗口期延迟 | 默认1秒 | 1秒内DB库存与Redis计数不一致 | sq>=net_deduction防线保障不超卖 |
| 紧急降级手动操作风险 | SET lq=0需同步更新lockOrder状态 | 操作遗漏导致不一致 | 文档明确步骤顺序+操作检查清单 |
| 单桶扣减限制 | quantity > 单桶余量时降级DB | 大额购买走DB路径 | 单桶容量 >= 常见最大购买数量 |
| QPS测量精度 | 滑动窗口1秒粒度 | 短时流量波动可能误触发合并 | 可调低idle-qps-threshold |
| refund_request_id为NULL时无业务幂等 | MySQL NULL不参与唯一约束 | 重复退款风险 | 建议调用方始终传入 |

---

## 五、总结

### 修复统计

| 轮次 | 识别问题数 | P0 | P1 | P2 | P3 |
|------|-----------|----|----|----|----|
| 第五轮 | 19 | 2 | 5 | 6 | 6 |
| 第六轮 | 8 | 1 | 3 | 2 | 2 |
| **合计** | **27** | **3** | **8** | **8** | **8** |

### 当前spec.md质量评估

- **逻辑正确性**：✅ 核心扣减链路全场景推演通过，无已知逻辑BUG
- **内部一致性**：✅ 伪代码/场景描述/核心数据流/约束条件四维交叉验证通过
- **实现完备性**：✅ 所有实现细节已定义（QPS测量、超时统计、路由补偿、紧急降级开关等）
- **基础设施兼容性**：✅ Redis Cluster Hash Tag方案完整
- **防御性编程**：✅ 四字段非负约束（sq/wq/oq/lq）+ COALESCE + bucket_index校验 + Step 4a.5幂等跳过

### 建议下一步

当前spec.md已达到**可实施**质量水平。建议：

1. **编码启动前**：通读spec.md最终版本，确认所有修复点已理解
2. **编码过程中**：重点关注Lua脚本实现（Hash Tag Key构造）、合并流程Step 4a.5、紧急降级开关检查三个关键修复点
3. **测试阶段**：针对六轮评审发现的所有问题场景编写专项测试用例
