# 高并发场景逻辑自洽性与 BUG 分析报告（第三轮）

> 分析日期：2026-05-01
> 分析对象：基于 Redis 分布式强一致库存扣减系统设计文档（spec.md 修订版，已整合前两轮修复）
> 分析方法：逐场景推演高并发竞态时序，验证逻辑闭环
> 前置参考：concurrency-analysis.md、review.md、verification-report.md

---

## 一、前两轮修复验证结论

前两轮共识别 **22个问题**（14个并发问题 + 8个Review问题），核心修复包括：

| 修复项 | 状态 | 验证结果 |
|--------|------|---------|
| lq减量更新（`lq = lq - currentLockQuantity`） | ✅ 已修复 | 多lockOrder并存不再超卖 |
| 补偿合并安全约束（WHERE sq >= net_deduction） | ✅ 已修复 | sq不会变负 |
| 锁库存严格时序 + 幂等键 | ✅ 已修复 | 消除锁库存竞态 |
| 条件INCR回补（检查meta有效性） | ✅ 已修复 | 避免INCR与清桶竞态 |
| 扣减明细幂等索引 `(order_id, sku_id)` | ✅ 已修复 | 防止重复扣减 |
| total_remaining Key原子余量 | ✅ 已修复 | 余量检测精确 |
| merge_completed崩溃恢复标记 | ✅ 已修复 | 崩溃后自动补偿清理 |

**以上修复经验证报告推演全部通过。** 以下分析聚焦于当前版本中**仍存在的或新引入的**问题。

---

## 二、当前版本新发现的问题

### 🔴 P0 级：致命逻辑矛盾（可直接导致超卖）

#### 问题 N1：紧急解锁 `SET lq=0` 与 Redis 分桶残留的超卖风险

**位置**：spec.md → 一致性保障机制 → 紧急降级方案

**现状描述**：

> 紧急解锁接口：提供 `emergencyUnlock(skuId)` 管理接口，将所有ACTIVE lockOrder触发紧急合并提交（**或直接** `UPDATE inventory SET lq = 0 WHERE id = #{skuId}`）

**矛盾推演**：

```
初始状态: sq=10000, lq=9000 (lockOrder-A, reserve-ratio=0.1)
Redis分桶: 16桶 × 562/563, total_remaining=9000

T=0s  Redis集群故障
T=1s  人工触发紧急解锁: UPDATE inventory SET lq = 0
      → inventory: sq=10000, wq=0, lq=0
      → sq - lq = 10000

T=1.001s  Redis部分恢复（网络分区修复）
      → 扣减请求路由到lockOrder-A的Redis分桶
      → Lua扣减成功（桶内仍有库存计数）
      → 同时DB降级路径也可扣减（sq-lq=10000）
      → 🔴 Redis路径和DB路径同时扣减同一批库存！超卖！

更极端：Redis完全恢复后
      → lockOrder-A仍ACTIVE，分桶仍有9000库存计数
      → DB降级路径看到sq-lq=10000，可扣减10000
      → Redis路径可扣减9000
      → 总可扣减量 = 10000 + 9000 = 19000 > sq=10000
      → 🔴 超卖9000件！
```

**根因**：`SET lq = 0` 只释放了DB层面的lq保护，但Redis分桶中的库存计数仍然存在。lq是防止DB降级路径侵占Redis预锁库存的唯一屏障，直接清零lq等于同时打开了Redis和DB两条扣减通道，且它们互不感知。

**最优修复方案**：

1. **禁止直接 `SET lq = 0` 选项**：紧急解锁必须走合并提交流程（先失效分桶索引→合并提交→清零Redis分桶→释放lq），确保Redis分桶和lq同步释放
2. 紧急解锁接口改为：`emergencyUnlock(skuId)` → 对所有ACTIVE lockOrder逐个触发紧急合并提交（按lockOrderId维度加分布式锁，串行处理）
3. 紧急合并提交与正常合并提交逻辑一致，但跳过延迟等待，立即执行
4. 如果必须快速释放（合并提交耗时过长），应**同时清零所有ACTIVE lockOrder的Redis分桶**（Lua脚本批量DEL），然后再SET lq=0，且在清零Redis分桶期间设置全局降级开关（`inventory:emergency_degrade:{skuId}` = true，TTL=30s），暂停Redis路径扣减

---

#### 问题 N2：lockOrderId 生成方式未定义，导致锁库存严格时序无法执行

**位置**：spec.md → 锁库存操作严格时序

**现状描述**：

```
Step 1: Redis Lua脚本原子初始化分桶
        Key格式: inventory:lock:{lockOrderId}:bucket:{n}
Step 2: DB事务内 INSERT lock_inventory_order
```

**矛盾推演**：

Step 1 需要 lockOrderId 来构造 Redis Key，但 lockOrderId 的生成方式未定义：

- **如果使用数据库自增ID**：Step 1 执行时 lockOrderId 尚未生成（INSERT 在 Step 2），**时序设计不可执行**
- **如果使用预生成ID（UUID/雪花算法）**：两个并发幂等请求（同一 idempotentKey）会生成**不同的** lockOrderId，各自执行 Step 1 初始化不同的 Redis 分桶。只有一个 Step 2 INSERT 成功，失败的那个需要清理自己的 Redis 分桶——但文档未描述此场景的清理逻辑

```
并发幂等请求推演（预生成ID）:
T=0s  请求1(idempotentKey=IK-001): 生成lockOrderId=A
      Step 1: Redis初始化 inventory:lock:A:bucket:0..15
T=0.001s  请求2(idempotentKey=IK-001): 生成lockOrderId=B
      幂等检查SELECT → 未命中（请求1尚未INSERT）
      Step 1: Redis初始化 inventory:lock:B:bucket:0..15
T=0.01s  请求1 Step 2: INSERT lock_inventory_order(id=A, idempotent_key=IK-001) → 成功
T=0.011s 请求2 Step 2: INSERT lock_inventory_order(id=B, idempotent_key=IK-001) → 唯一索引冲突！
      → DB事务回滚
      → 🔴 但 inventory:lock:B:bucket:0..15 的Redis分桶已初始化，谁来清理？
      → 文档只说"DB事务失败时使用Lua清理脚本回滚Step 1的Redis分桶"
      → 但请求2需要知道自己的lockOrderId=B才能清理，这个逻辑未定义
```

**根因**：锁库存严格时序假设 Step 1 知道 lockOrderId，但未定义 lockOrderId 的生成时机和幂等冲突时的 Redis 清理逻辑。

**最优修复方案**：

1. **明确使用预生成ID（雪花算法）**：在 Step 1 之前生成 lockOrderId，确保 Redis Key 可构造
2. **幂等冲突时的Redis清理**：Step 2 的 DB 事务失败时（包括唯一索引冲突），使用预生成的 lockOrderId 执行 Lua 原子清理脚本回滚 Step 1 的 Redis 分桶。具体逻辑：
   - INSERT 失败捕获 `DuplicateKeyException`
   - 使用当前请求预生成的 lockOrderId 构造 Redis Key 前缀 `inventory:lock:{lockOrderId}:*`
   - 执行 Lua 原子清理脚本删除所有相关 Key（bucket keys、meta key、total_remaining key）
3. **幂等检查前置优化**：在 Step 1 之前先执行幂等检查 `SELECT id FROM lock_inventory_order WHERE idempotent_key = #{idempotentKey}`，如果已存在则直接返回已有 lockOrderId，避免无谓的 Redis 初始化和清理

---

### 🟠 P1 级：严重逻辑缺陷（高并发下大概率触发少卖/数据不一致）

#### 问题 N3：扣减明细幂等检查时序描述自相矛盾

**位置**：spec.md → 核心数据流 vs 插入合并下单明细场景

**矛盾点**：

**核心数据流**将幂等检查放在 Lua 扣减**之前**：
```
→ 【幂等检查】SELECT 1 FROM deduction_detail WHERE order_id = #{orderId} AND sku_id = #{skuId}
  → IF 已存在: 直接返回成功（幂等），并INCR回补Redis分桶计数
→ 【路由解析】...
→ 随机选择一个桶，执行Lua脚本原子扣减
```

**插入合并下单明细场景**将幂等检查放在 Lua 扣减**之后**：
```
插入前先查询 (order_id, sku_id) 唯一索引是否已存在记录，
若已存在则直接返回成功（幂等），并INCR回补Redis分桶计数（因为Lua扣减已成功但上次DB插入失败）
```

**矛盾推演**：

```
场景：请求超时重试（同一orderId+skuId）

按核心数据流（先幂等后Lua）:
  T1: 幂等检查 → 不存在 → Lua扣减成功 → DB插入成功 → 返回超时
  T2: 幂等检查 → 已存在 → 直接返回成功 → ✅ 不执行Lua，不需要INCR回补
  → 正确！但文档说"并INCR回补Redis分桶计数"——此时Lua未执行，为何需要INCR？

按场景描述（先Lua后幂等）:
  T1: Lua扣减成功 → 幂等检查 → 不存在 → DB插入失败（网络超时）
  T2: Lua扣减成功（又扣了一次）→ 幂等检查 → 已存在 → INCR回补 → DB插入冲突
  → Redis净变化: -1(Lua) -1(Lua) +1(INCR) = -1 → ✅ 正确
  → 但多执行了一次Lua扣减和一次INCR，增加了Redis操作开销
```

**根因**：两处描述对幂等检查的位置和INCR回补的触发条件不一致，实现时会产生歧义。

**最优修复方案**：

统一为**先幂等检查后Lua扣减**（高效路径），同时依赖DB唯一索引作为最终幂等保障（防御路径）：

```
1. SELECT幂等检查 → 已存在 → 直接返回成功（无需INCR，因为Lua未执行）
2. 不存在 → Lua扣减 → DB INSERT
3. INSERT唯一索引冲突 → 说明是重试且上次实际成功 → INCR回补本次Lua扣减 → 返回成功
```

核心数据流中的"并INCR回补Redis分桶计数"描述应删除（幂等命中时Lua未执行，无需INCR）。INCR回补仅在DB INSERT唯一索引冲突时触发（防御路径）。

---

#### 问题 N4：手动锁库存不考虑 reserve-ratio，可能锁死全部可用额度

**位置**：spec.md → 锁库存管理模块 vs 自动锁库存模块

**矛盾点**：

- 自动锁库存：`actualLockQuantity = min(lockQuantity, (sq - lq) * (1 - reserve-ratio))`，预留10%给DB降级
- 手动锁库存：`actualLockQuantity = min(lockQuantity, sq - lq)`，**不预留**任何额度

**矛盾推演**：

```
sq=10000, lq=0
手动锁库存: lockQuantity=10000
→ actualLockQuantity = min(10000, 10000) = 10000
→ lq=10000, sq-lq=0

此时Redis不可用:
→ Redis分桶扣减: 失败
→ DB降级扣减: WHERE sq - lq >= 1 → 0 >= 1 → 失败
→ 🔴 完全不可用！
```

**根因**：手动锁库存没有应用 reserve-ratio，可以锁定全部可用额度，使DB降级路径完全不可用。

**最优修复方案**：

1. 手动锁库存接口增加可选参数 `reserveRatio`（默认值取 `store.auto-lock.reserve-ratio` 配置），计算公式与自动锁库存一致：`actualLockQuantity = min(lockQuantity, (sq - lq) * (1 - reserveRatio))`
2. 调用方可以显式传入 `reserveRatio=0` 来锁定全部额度（适用于明确不需要DB降级路径的场景），但需在接口文档中标注风险
3. 锁库存接口返回值中包含 `actualLockQuantity` 和 `reservedQuantity`（预留额度 = (sq-lq) * reserveRatio），让调用方感知实际锁定量和预留量

---

#### 问题 N5：max-active 约束缺乏强制保障，并发创建可能超额

**位置**：spec.md → 自动锁库存模块 → 滚动锁库存策略

**现状描述**：

> 同一SKU同时最多存在 `store.auto-lock.max-active`（默认2）个ACTIVE状态的lockOrder

**矛盾推演**：

```
T=0s  当前: lockOrder-A(ACTIVE), max-active=2
T=0.5s  lockOrder-A的total_remaining降至50%
      线程1(同步快检): 查询ACTIVE lockOrder数量=1 < 2 → 创建lockOrder-B
      线程2(定时任务): 查询ACTIVE lockOrder数量=1 < 2 → 创建lockOrder-C
T=0.6s  lockOrder-B创建成功: lq增加10000
T=0.6s  lockOrder-C创建成功: lq增加10000
      → 3个ACTIVE lockOrder！超过max-active=2
      → lq比预期多10000，sq-lq比预期少10000
      → DB降级路径可用额度减少 → 少卖风险
```

**根因**：max-active 约束仅靠应用层检查（查询ACTIVE数量），没有分布式锁或DB约束保障原子性。高并发下多个触发源（同步快检 + 定时任务）可能同时通过检查。

**最优修复方案**：

1. **创建lockOrder时使用分布式锁**：key=`auto-lock-create:{skuId}`，串行化同一SKU的锁库存创建操作
2. **锁库存DB事务内增加ACTIVE数量检查**：在 Step 2 的 DB 事务内，INSERT之前执行 `SELECT COUNT(*) FROM lock_inventory_order WHERE sku_id = #{skuId} AND status = 'ACTIVE' FOR UPDATE`，如果数量已达max-active则回滚事务并清理Redis
3. 分布式锁的持有时间应覆盖整个锁库存操作（Step 1 + Step 2 + Step 3），确保检查和创建的原子性

---

### 🟡 P2 级：设计缺陷（特定场景下影响可用性或准确性）

#### 问题 N6：路由缓存 SET 与历史列表 APPEND 非原子，可能丢失兜底路由

**位置**：spec.md → 活跃lockOrder路由机制 → 活跃lockOrder切换场景

**现状描述**：

```
原子执行 SET inventory:active_lock:{skuId} = lockOrder-B
将lockOrder-B追加到 inventory:active_lock_history:{skuId}
```

**矛盾推演**：

```
T=0s  SET active_lock = lockOrder-B → 成功
T=0.001s  APPEND active_lock_history = [A, B] → Redis超时 → 失败
      → 活跃路由指向lockOrder-B，但历史列表中只有[A]

T=1s  lockOrder-B合并提交中，分桶索引失效
      → 扣减请求检查活跃路由 → lockOrder-B分桶索引无效
      → 查询历史路由 → [A]（lockOrder-B不在历史列表中）
      → 遍历历史路由 → lockOrder-A也已ARCHIVED
      → 降级走DB
      → 🔴 lockOrder-B在合并提交前仍有余量，但无法被路由到
```

**根因**：SET和APPEND是两个独立的Redis操作，非原子执行。APPEND失败时活跃路由已更新，但历史列表缺失最新lockOrder。

**最优修复方案**：

使用Redis Lua脚本将SET和APPEND封装为原子操作：

```lua
-- KEYS[1] = inventory:active_lock:{skuId}
-- KEYS[2] = inventory:active_lock_history:{skuId}
-- ARGV[1] = lockOrderId
-- ARGV[2] = TTL for active_lock key
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('RPUSH', KEYS[2], ARGV[1])
-- 保持历史列表最多N个（LRTRIM）
local maxHistory = tonumber(ARGV[3] or '5')
local len = redis.call('LLEN', KEYS[2])
if len > maxHistory then
    redis.call('LTRIM', KEYS[2], len - maxHistory, -1)
end
return 1
```

同时，活跃路由失效时的兜底逻辑应增加**DB查询ACTIVE lockOrder**作为最终兜底（当前已有此设计，但应明确优先级：活跃路由缓存 → 历史路由列表 → DB查询ACTIVE lockOrder → 降级DB直接扣减）。

---

#### 问题 N7：PENDING 取消条件 INCR 回补的 meta 检查与 INCR 执行非原子

**位置**：spec.md → PENDING状态取消场景

**现状描述**：

> 先检查分桶索引缓存（meta）是否仍然有效
> IF 有效：执行INCR回补
> IF 已失效：跳过INCR回补

**矛盾推演**：

```
T=1.000s  PENDING取消请求: GET meta → 有效
T=1.001s  合并提交: DEL meta（失效分桶索引）
T=1.002s  PENDING取消请求: INCRBY bucket:3 10, INCRBY total_remaining 10
          → 桶计数恢复10件
T=1.003s  穿透屏障的扣减请求: Lua扣减bucket:3 → 成功（因为INCR恢复了库存）
          → 插入PENDING明细 → 孤立PENDING明细
T=1.004s  合并提交: DEL bucket:3, DEL total_remaining
          → INCR回补的结果被DEL覆盖
```

**影响分析**：
- INCR回补被DEL覆盖 → 回补丢失，但合并提交的"先标记后计算"已排除了CANCELLED明细，sq不会多减 → **不影响正确性**
- INCR回补短暂恢复了桶计数，允许穿透请求扣减成功 → 产生孤立PENDING明细 → 需要补偿合并处理 → **增加补偿负担但不超卖**

**最优修复方案**：

使用Redis Lua脚本将"检查meta + INCR回补"封装为原子操作：

```lua
-- KEYS[1] = meta key (inventory:lock:{lockOrderId}:meta)
-- KEYS[2] = bucket key (inventory:lock:{lockOrderId}:bucket:{n})
-- KEYS[3] = total_remaining key
-- ARGV[1] = refund quantity
local metaExists = redis.call('EXISTS', KEYS[1])
if tonumber(metaExists) == 1 then
    redis.call('INCRBY', KEYS[2], ARGV[1])
    redis.call('INCRBY', KEYS[3], ARGV[1])
    return 1
else
    return 0
end
```

返回1表示INCR回补成功，返回0表示meta已失效跳过回补。此方案消除了meta检查与INCR执行之间的时间窗口，避免INCR作用于即将被清除的分桶。

---

#### 问题 N8：MERGED/CANCELLED 状态取消时缺乏 wq/oq 非负约束

**位置**：spec.md → 状态转换规则

**现状描述**：

```sql
-- MERGED取消
UPDATE inventory SET wq = wq - #{quantity}, sq = sq + #{quantity}

-- OCCUPIED退款
UPDATE inventory SET oq = oq - #{quantity}, sq = sq + #{quantity}
```

**矛盾推演**：

```
正常情况: wq=5000, 取消quantity=100 → wq=4900 ✅

异常情况（如合并提交bug导致wq偏低）:
wq=50, 取消quantity=100 → wq=-50 🔴 wq变负！
→ CHECK(wq >= 0)约束触发 → UPDATE失败 → 事务回滚
→ 但如果MySQL未启用CHECK约束（默认不强制）→ wq=-50 持久化 → 数据污染
```

**根因**：取消/退款SQL没有 `WHERE wq >= #{quantity}` / `WHERE oq >= #{quantity}` 约束，依赖CHECK约束但MySQL默认不强制执行CHECK。

**最优修复方案**：

1. MERGED取消SQL增加wq非负约束：
   ```sql
   UPDATE inventory SET wq = wq - #{quantity}, sq = sq + #{quantity}
   WHERE id = #{skuId} AND wq >= #{quantity}
   ```
2. OCCUPIED退款SQL增加oq非负约束：
   ```sql
   UPDATE inventory SET oq = oq - #{quantity}, sq = sq + #{quantity}
   WHERE id = #{skuId} AND oq >= #{quantity}
   ```
3. UPDATE影响行数为0时触发告警，进入人工处理流程（与合并提交sq不足的处理策略一致）
4. 此约束与已有的 `WHERE sq >= #{net_deduction}` 防线形成完整的非负保护体系

---

#### 问题 N9：活跃路由只指向最新 lockOrder，旧 lockOrder 剩余库存利用不足

**位置**：spec.md → 活跃lockOrder路由机制

**现状描述**：

`inventory:active_lock:{skuId}` 只存储一个 lockOrderId，新 lockOrder 创建后路由切换，旧 lockOrder 不再被新请求路由到。

**矛盾推演**：

```
T=0s    lockOrder-A创建: total_remaining=10000
T=0.5s  lockOrder-A total_remaining=3000（30%剩余）
        lockOrder-B创建: total_remaining=10000
        路由切换到lockOrder-B

T=0.5~1.0s  所有新请求路由到lockOrder-B
        lockOrder-A的3000件剩余库存无法被新请求使用
        → 只能等lockOrder-A合并提交后，通过sq-lq释放

对比：如果路由同时指向A和B
        → 请求可同时利用A的3000和B的10000
        → Redis总可用量=13000 vs 当前设计的10000
        → 🔴 少卖3000件的Redis加速能力
```

**根因**：单活跃路由设计简单但无法充分利用多lockOrder的Redis库存。

**最优修复方案**：

**接受当前设计作为设计权衡**，理由如下：

1. 旧lockOrder余量通常较少（触发新lockOrder创建时已降至50%以下），利用价值有限
2. 多路由增加扣减路径复杂度（需要负载均衡、余量检测、fallover跨lockOrder），增加延迟
3. 旧lockOrder余量在合并提交后释放到sq-lq，DB降级路径仍可使用
4. 历史路由兜底机制已能在活跃路由失效时回退到旧lockOrder

在文档中明确标注此设计权衡，并说明：如果未来需要充分利用旧lockOrder余量，可将路由缓存改为有序列表。

---

### 🟢 P3 级：设计模糊点（需明确才能正确实现）

#### 问题 N10：锁库存预校验的 actualLockQuantity 计算与 DB UPDATE 可能不一致

**位置**：spec.md → 锁库存操作严格时序

**模糊点**：

Step 1 的 Redis 初始化数量基于预校验计算的 actualLockQuantity，但 Step 2 的 DB UPDATE 受 `WHERE sq - lq >= #{actualLockQuantity}` 约束。如果预校验和 UPDATE 之间 sq-lq 发生变化：

- sq-lq 减小 → UPDATE 影响行数为0 → 事务失败 → Redis 清理 ✅ 正确处理
- sq-lq 增大（其他lockOrder合并提交释放了lq）→ UPDATE 成功，但锁定量可能少于新的可用额度 → 不是BUG，下次自动锁库存会补充

**最优修复方案**：

在文档中明确说明此场景的处理策略：

1. 预校验值与DB实际值不一致是正常现象（并发环境下sq-lq随时变化）
2. Step 2 的 DB UPDATE 使用 `WHERE sq - lq >= #{actualLockQuantity}` 约束，确保不会超锁
3. 如果实际可用额度大于预校验值，差额由后续自动锁库存补充（连锁触发机制保障）
4. 如果实际可用额度小于预校验值，DB UPDATE失败，Redis分桶被清理，不会产生不一致

---

#### 问题 N11：合并提交 Step 4c 读取的 lockQuantity 假设不可变

**位置**：spec.md → 合并流程伪代码 Step 4c

**模糊点**：

```sql
SELECT lock_quantity AS currentLockQuantity FROM lock_inventory_order WHERE id = #{lockOrderId}
```

此查询假设 lockQuantity 在 lockOrder 创建后不会修改。如果实现上允许修改 lockQuantity，lq 减量更新会使用错误的值。

**最优修复方案**：

1. 在 lock_inventory_order 表定义中明确 `lock_quantity` 为**不可变字段**（创建后不可UPDATE）
2. 在数据模型文档中标注：`lock_quantity` 字段为创建时一次性写入，后续只读
3. 如果因业务需要必须修改 lockQuantity，需同时调整 lq 差额（`UPDATE inventory SET lq = lq - #{oldQuantity} + #{newQuantity}`），但这会引入复杂的一致性问题，建议禁止

---

#### 问题 N12：扣减请求同步快检的 fire-and-forget 事件丢失场景

**位置**：spec.md → 自动锁库存模块 → 连锁触发机制

**模糊点**：

同步快检发现 total_remaining 低于阈值后，发送异步事件（fire-and-forget）。如果事件丢失（线程池满、MQ故障），自动锁库存触发延迟到定时任务兜底（默认500ms）。

在10K TPS下，500ms延迟意味着约5000个请求可能降级到DB路径。

**最优修复方案**：

1. 在文档中明确这是设计权衡：fire-and-forget 保证扣减请求主路径零延迟，事件丢失由定时任务兜底
2. 给出定时任务间隔的调优建议：高TPS场景下可将 `store.auto-lock.check-interval-ms` 缩短至100-200ms
3. 增加监控指标 `store.auto-lock.event.drop.count`（异步事件丢弃次数），当丢弃率过高时告警
4. 异步事件使用 Spring ApplicationEvent + 线程池，线程池配置建议：核心线程数=CPU核心数，队列容量=1000，拒绝策略=CallerRunsPolicy（降级为同步触发）

---

## 三、已修复问题的残留风险评估

| 已修复问题 | 残留风险 | 风险等级 |
|-----------|---------|---------|
| lq减量更新 | currentLockQuantity读取假设不可变（问题N11） | 🟢 低 |
| 条件INCR回补 | meta检查与INCR非原子（问题N7） | 🟡 中（不影响正确性） |
| 紧急降级方案 | SET lq=0选项存在超卖风险（问题N1） | 🔴 高 |
| 锁库存幂等 | 并发幂等请求的Redis清理未定义（问题N2） | 🔴 高 |
| 扣减明细幂等 | 幂等检查时序描述矛盾（问题N3） | 🟠 中 |

---

## 四、问题汇总矩阵

| 编号 | 严重度 | 问题 | 后果 | 影响链路阶段 | 修复优先级 |
|------|--------|------|------|-------------|-----------|
| N1 | 🔴 P0 | 紧急解锁SET lq=0与Redis分桶残留 | **超卖** | 紧急降级 | 最高 |
| N2 | 🔴 P0 | lockOrderId生成方式未定义 | **Redis分桶残留/少卖** | 锁库存 | 最高 |
| N3 | 🟠 P1 | 扣减幂等检查时序自相矛盾 | **实现歧义/潜在超卖** | 下单扣减 | 高 |
| N4 | 🟠 P1 | 手动锁库存不考虑reserve-ratio | **DB降级完全不可用** | 锁库存 | 高 |
| N5 | 🟠 P1 | max-active约束缺乏强制保障 | **lq超额/少卖** | 自动锁库存 | 高 |
| N6 | 🟡 P2 | 路由SET与历史APPEND非原子 | **兜底路由丢失/少卖** | 路由机制 | 中 |
| N7 | 🟡 P2 | 条件INCR回补meta检查与INCR非原子 | **孤立明细增加** | 取消/合并提交 | 中 |
| N8 | 🟡 P2 | 取消/退款缺乏wq/oq非负约束 | **字段变负/数据污染** | 取消/退款 | 中 |
| N9 | 🟡 P2 | 单活跃路由旧lockOrder库存利用不足 | **Redis加速能力浪费** | 路由机制 | 低（设计权衡） |
| N10 | 🟢 P3 | 预校验值与DB实际值不一致策略未文档化 | **实现歧义** | 锁库存 | 低 |
| N11 | 🟢 P3 | lockQuantity不可变性未明确 | **lq减量错误** | 合并提交 | 低 |
| N12 | 🟢 P3 | 快检事件丢失的延迟影响未量化 | **降级路径压力** | 自动锁库存 | 低 |

---

## 五、核心结论

### 1. 整体评价

当前 spec.md 在前两轮修复后，**核心扣减链路的正确性已基本保障**——"先标记后计算"机制、lq减量更新、条件INCR回补、幂等索引等关键设计均经过推演验证。但仍存在 **2个P0级致命问题** 和 **3个P1级严重问题** 需要修复。

### 2. 最优先修复项

1. **问题N1（紧急解锁超卖）**：删除 `SET lq = 0` 选项，紧急解锁必须走合并提交流程或同时清零Redis分桶
2. **问题N2（lockOrderId生成）**：明确使用预生成ID（雪花算法），定义幂等冲突时的Redis清理逻辑
3. **问题N3（幂等检查时序）**：统一为先幂等检查后Lua扣减，DB唯一索引作为最终保障

### 3. 设计权衡确认

以下问题属于设计权衡，不影响正确性，建议在文档中明确标注而非修复：
- **问题N7**（条件INCR非原子）：不影响正确性，仅增加补偿负担
- **问题N9**（单活跃路由）：简化路由逻辑的trade-off
- **问题N12**（快检事件丢失）：有定时任务兜底，是性能与复杂度的权衡

### 4. 与前两轮分析的关系

前两轮识别的22个问题中，核心架构矛盾（lq聚合值与per-lockOrder设计的模型不匹配）已通过lq减量更新彻底解决。本轮新发现的问题主要集中在**边界场景的防御完备性**（紧急降级、幂等冲突清理）和**文档一致性**（时序描述矛盾、约束缺失），属于"长尾问题"，不影响主链路的正确性。
