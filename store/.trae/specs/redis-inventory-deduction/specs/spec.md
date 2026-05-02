# 基于Redis分布式强一致库存扣减系统 Spec

## Why

针对热点深库存下单抢购场景（如直播带货、秒杀活动），传统数据库直接扣减方式性能瓶颈明显，需要通过分布式缓存（Redis）提升扣减TPS，同时保证**强一致性**：绝对不允许超卖或少卖。

## What Changes

- 实现基于Redis分桶的库存合并扣减架构
- 引入锁库存、Redis预扣减、DB明细记录、异步合并提交四大核心模块
- 引入自动锁库存模块和活跃lockOrder路由机制，消除合并提交空窗期
- 支持复杂库存模型（可售库存sq、预扣库存wq、占用库存oq、预锁库存lq）
- **BREAKING**: 需要新增Redis基础设施和数据库表结构（lock_inventory_order、deduction_detail、refund_detail三张独立表）

## Impact

- Affected specs: 库存扣减核心链路、缓存一致性保障机制
- Affected code: store模块整体架构、数据访问层、业务逻辑层

***

## ADDED Requirements

### Requirement: 锁库存管理模块 (Inventory Locking)

系统 SHALL 提供锁库存能力，支持将DB行库存从可售字段(sq)预锁定到预锁字段(lq)。

#### 核心设计原则
- **锁库存操作不减少sq值**：直接增加lq字段，通过 `WHERE sq - lq >= #{lockQuantity}` 控制锁定量不超过可售量
- **查询链路无感知**：库存展示/查询完全不受锁库存影响，无需关心lq值
- **Redis初始化**：每次锁定的lq数量必须同步初始化到Redis分桶中用于扣减计数
- **锁周期闭环**：合并提交后lq减去当前lockOrder的lockQuantity，未卖出的库存自然保留在sq中，等待下一轮锁库存初始化
- **多单据并行隔离**：分桶Key包含lockOrderId维度（`inventory:{lockOrderId}:lock:bucket:{n}`），同一SKU的不同lockOrder拥有各自独立的分桶，合并失效时仅影响当前lockOrder的分桶，不影响其他lockOrder的扣减能力
- **部分锁定支持**：当可用额度 `sq - lq < lockQuantity` 但 `sq - lq > 0` 时，自动调整为实际可用额度作为锁定量，避免可用额度浪费
- **预留DB降级额度**：手动锁库存同样应预留一定比例的可用额度给DB降级路径，接口增加可选参数 `reserveRatio`（默认值取 `store.auto-lock.reserve-ratio` 配置），计算公式与自动锁库存一致：`actualLockQuantity = min(lockQuantity, (sq - lq) * (1 - reserveRatio))`。调用方可显式传入 `reserveRatio=0` 锁定全部额度（适用于明确不需要DB降级路径的场景），但需在接口文档中标注风险。锁库存接口返回值包含 `actualLockQuantity` 和 `reservedQuantity`（预留额度 = (sq-lq) * reserveRatio），让调用方感知实际锁定量和预留量
- **reserve-ratio与min-lock-quantity交互死区**：当 `min-lock-quantity / (1 - reserveRatio) > sq - lq >= min-lock-quantity` 时，存在"死区"——可用额度足够但锁库存失败（如reserve-ratio=0.1, min-lock-quantity=100时，100 <= sq-lq < 112 的范围约12件无法被Redis锁定）。死区大小约为 `min-lock-quantity * reserveRatio / (1 - reserveRatio)`，默认配置下约11件，影响极小。如需消除死区，可将min-lock-quantity降低或reserve-ratio设为0

#### 并发控制策略
- **SQL行锁防护**：InnoDB行锁保证并发UPDATE串行执行，`WHERE sq - lq >= #{lockQuantity}` 确保不会超锁
- **应用层预校验**：锁库存前先查询 `sq - lq` 的值，若小于lockQuantity则尝试部分锁定（实际锁定量 = min(lockQuantity, (sq - lq) * (1 - reserveRatio))，reserve-ratio始终生效，包括部分锁定场景），若计算结果低于最小有效锁定量（`store.auto-lock.min-lock-quantity`，默认100）则直接返回错误
- **错误码定义**：锁库存失败时返回 `LOCK_QUANTITY_EXCEEDED`（可用额度不足）
- **幂等保障**：锁库存请求携带 `idempotentKey`，在 `lock_inventory_order` 表的 `idempotent_key` 唯一索引约束下保证同一请求不会重复创建lockOrder

#### 锁库存操作严格时序

锁库存操作必须按以下严格顺序执行，任何前置步骤失败不得继续后续步骤：

> **lockOrderId生成方式**：使用**预生成ID（雪花算法）**，在Step 1之前生成lockOrderId，确保Redis Key可构造。禁止使用数据库自增ID（Step 1执行时ID尚未生成，时序设计不可执行）。
>
> **幂等冲突时的Redis清理**：当Step 2的INSERT因idempotent_key唯一索引冲突失败时，使用当前请求预生成的lockOrderId执行Lua原子清理脚本回滚Step 1的Redis分桶（bucket keys、meta key、total_remaining key）。幂等检查应在Step 1之前执行（`SELECT id FROM lock_inventory_order WHERE idempotent_key = #{idempotentKey}`），如果已存在则直接返回已有lockOrderId，避免无谓的Redis初始化和清理。

```
Step 0: 幂等检查: SELECT id, status FROM lock_inventory_order WHERE idempotent_key = #{idempotentKey}
        → IF 已存在且status=ACTIVE: 直接返回已有lockOrderId，不重复执行
        → IF 已存在且status=ARCHIVED: 返回错误码 `LOCK_ORDER_ALREADY_ARCHIVED`，提示调用方使用新的idempotentKey重新发起锁库存请求
Step 1: Redis Lua脚本原子初始化分桶 + 分桶索引缓存(meta) + 总余量Key(total_remaining)
        → 使用预生成的lockOrderId构造Redis Key
        → 必须最先执行，确保Redis侧资源就绪
Step 2: DB事务内执行:
        a. UPDATE inventory SET lq = lq + #{actualLockQuantity}
           WHERE id = #{skuId} AND sq - lq >= #{actualLockQuantity}
        b. INSERT lock_inventory_order（status=ACTIVE, lock_quantity=#{actualLockQuantity}, idempotent_key=#{idempotentKey}）
        → UPDATE和INSERT必须在同一DB事务中，保证原子性
        → 事务失败时（包括唯一索引冲突），使用Lua原子清理脚本回滚Step 1的Redis分桶（使用预生成的lockOrderId构造Key）
Step 3: 原子更新路由缓存: 使用Lua脚本原子执行 SET inventory:active_lock:{skuId} = newLockOrderId + RPUSH inventory:active_lock_history:{skuId} = newLockOrderId
        → 必须在Step 1和Step 2全部完成后才能执行
        → 路由更新是锁库存操作的最后一步
        → 任何前置步骤失败，不更新路由缓存
        → Step 3失败处理：对Lua脚本执行增加重试机制（最多3次，间隔100ms）。若重试仍失败，后台补偿任务定期扫描 lock_inventory_order WHERE status='ACTIVE' AND created_at < NOW() - INTERVAL 5 SECOND 的记录，检查其lockOrderId是否在路由缓存中，若不在则补偿更新路由缓存。应用启动时也应在崩溃恢复逻辑中检查并修复缺失的路由缓存
```

> **预校验值与DB实际值不一致**：Step 1的Redis初始化数量基于预校验计算的actualLockQuantity，但Step 2的DB UPDATE受`WHERE sq - lq >= #{actualLockQuantity}`约束。如果预校验和UPDATE之间sq-lq发生变化（并发环境正常现象）：sq-lq减小→UPDATE影响行数为0→事务失败→Redis清理（正确处理）；sq-lq增大→UPDATE成功但锁定量少于新的可用额度→差额由后续自动锁库存补充（连锁触发机制保障）。

#### Scenario: 成功锁库存

- **WHEN** 业务方调用锁库存接口，传入商品ID、锁定数量和幂等键
- **THEN** 系统按严格时序执行：先Redis初始化分桶，再DB事务内增加lq并创建锁库存单据，最后更新路由缓存
- **AND** 系统在DB记录上增加lq字段值（`UPDATE inventory SET lq = lq + #{actualLockQuantity} WHERE id = #{skuId} AND sq - lq >= #{actualLockQuantity}`）
- **AND** 同时在Redis对应分桶中初始化等量库存计数
- **AND** 在lock\_inventory\_order表创建一条**锁库存单据**（记录lq变更量和关联的Redis分桶信息），作为父单据供后续扣减明细通过lock\_order\_id关联
- **AND** 返回锁库存单据ID用于后续扣减关联

#### Scenario: 锁库存失败（可售量不足）

- **WHEN** 业务方调用锁库存接口，传入锁定数量800
- **AND** 当前 `sq - lq = 500`（可用额度不足但大于最小有效锁定量）
- **THEN** 系统自动调整为部分锁定：actualLockQuantity = min(800, 500 * (1 - reserveRatio)) = min(800, 450) = 450（reserve-ratio始终生效，包括部分锁定场景）
- **AND** Redis分桶初始化450件，DB lq增加450，DB降级路径保留50件（500 * 0.1）

#### Scenario: 锁库存失败（可用额度极低）

- **WHEN** 业务方调用锁库存接口
- **AND** 当前 `sq - lq < store.auto-lock.min-lock-quantity`（可用额度低于最小有效锁定量）
- **THEN** DB UPDATE影响行数为0，系统返回错误码 `LOCK_QUANTITY_EXCEEDED`
- **AND** 不创建Redis分桶，不更新路由缓存

#### Scenario: 锁库存幂等（重复请求）

- **WHEN** 业务方因超时重试，使用相同idempotentKey再次调用锁库存接口
- **THEN** 系统通过 `lock_inventory_order.idempotent_key` 唯一索引检测到重复
- **AND** 直接返回已有lockOrderId，不重复执行锁库存操作
- **AND** 不重复增加lq，不重复创建Redis分桶

#### Scenario: 锁库存释放（主动释放）

- **WHEN** 业务方调用释放锁库存接口（如活动提前取消），传入锁库存单据ID
- **THEN** 系统触发该单据的合并提交流程（将已卖出的部分从sq转移到wq，lq减去当前lockOrder的lockQuantity）
- **AND** 对应Redis分桶清零/删除
- **AND** 未卖出的库存自然保留在sq中（sq只减实际卖出量）

#### Scenario: 锁库存超时自动释放

- **GIVEN** 锁库存单据设有过期时间（如30分钟）
- **WHEN** 超过过期时间且未主动触发合并提交
- **THEN** 后台定时任务扫描过期单据，自动触发合并提交释放库存
- **AND** 同时发出告警通知

### Requirement: 自动锁库存模块 (Auto Inventory Locking)

系统 SHALL 提供自动锁库存能力，通过热点识别提前锁定库存到Redis，并滚动创建新lockOrder消除合并提交空窗期。

#### 核心设计原则

- **热点识别驱动**：通过感知交易相关系统的热点品库存查询，提前将满足条件的商品库存进行自动锁库存
- **滚动管线**：提前创建新lockOrder，形成"当前lockOrder扣减 → 新lockOrder就绪 → 旧lockOrder合并提交"的滚动管线，消除空窗期
- **连锁触发**：只要一开始提前锁了任意数量的库存，后续下单扣减过程中，则会继续自动触发锁库存
- **可配置锁定量**：每次自动锁库存的lockQuantity通过 `store.auto-lock.quantity` 配置，默认值与分桶总容量一致
- **触发方式**：采用异步事件驱动 + 同步快检混合模式（详见下方"连锁触发机制"）

#### 连锁触发机制

自动锁库存的连锁触发采用以下混合模式：

1. **扣减请求中同步快检**：在扣减请求路径中，读取当前活跃lockOrder的 `total_remaining` Key。如果低于阈值（`store.auto-lock.trigger-ratio`），发送异步事件触发自动锁库存。此检查不阻塞扣减请求主路径（异步发送，fire-and-forget）。**事件去重**：发送事件前先SETNX `inventory:{skuId}:auto_lock_pending`（TTL=5s），已存在则跳过事件发送，避免高并发下多个扣减请求同时触发重复事件
2. **后台定时任务兜底**：定时任务（间隔 `store.auto-lock.check-interval-ms`，默认500ms）扫描所有活跃lockOrder的 `total_remaining`，触发自动锁库存。作为同步快检的兜底，防止事件丢失。**活跃lockOrder列表获取**：优先从路由缓存获取（快速、低开销），定期（如每30秒）从DB查询全量ACTIVE lockOrder（`SELECT id, sku_id FROM lock_inventory_order WHERE status = 'ACTIVE'`）补充路由缓存中缺失的条目，并交叉验证一致性
3. **不使用Redis Keyspace Notification**：在大规模Key场景下性能不可控，不采用

> **fire-and-forget事件丢失的trade-off**：同步快检使用异步事件（fire-and-forget）保证扣减请求主路径零延迟，事件丢失由定时任务兜底。在10K TPS下，500ms兜底延迟意味着约5000个请求可能降级到DB路径。调优建议：高TPS场景下可将 `store.auto-lock.check-interval-ms` 缩短至100-200ms。异步事件使用Spring ApplicationEvent + 线程池，线程池配置建议：核心线程数=CPU核心数，队列容量=1000，拒绝策略=CallerRunsPolicy（降级为同步触发）。增加监控指标 `store.auto-lock.event.drop.count`（异步事件丢弃次数），当丢弃率过高时告警。

#### 为什么需要自动锁库存？

在1w TPS热点扣减场景下，如果只有手动锁库存，会面临**合并提交空窗期**问题：

```
T=0s    Lock-A 创建（lq=10000, 16桶）  ← 扣减流量路由到Lock-A
T=1.0s  Lock-A 合并提交开始
        → 失效Lock-A分桶索引（扣减屏障生效）
        → 新的扣减请求检查分桶索引 → 已失效 → 降级走DB直接扣减
T=1.05s Lock-A 合并提交完成
        → 但Redis分桶已清除，没有新的分桶可用
        → 后续所有扣减请求继续降级走DB
T=???   手动创建新lockOrder
        → 扣减请求才能回到Redis路径

空窗期 = 合并提交耗时 + 新lockOrder创建耗时
期间1w TPS全部打到DB，可能击穿数据库
```

通过自动锁库存模块的滚动管线，空窗期被消除：

```
T=0s    Lock-A 创建（lq=10000, 16桶）  ← 扣减流量路由到Lock-A
T=0.5s  Lock-B 创建（lq=10000, 16桶）  ← 自动锁库存提前创建
T=1.0s  Lock-A 合并提交开始
        → 失效Lock-A分桶索引
        → 扣减流量自动路由到Lock-B（不同lockOrderId，独立分桶索引）
        → 无空窗期！
T=1.0s  Lock-A 合并提交完成
T=1.5s  Lock-C 创建（lq=10000, 16桶）  ← 自动锁库存提前创建
T=2.0s  Lock-B 合并提交开始
        → 扣减流量自动路由到Lock-C
        → 无空窗期！
...循环
```

#### 滚动锁库存策略

- **提前创建时机**：当前活跃lockOrder的分桶总余量低于阈值（`store.auto-lock.trigger-ratio`，默认50%）时，提前创建下一个lockOrder。余量检测通过 `inventory:lock:{lockOrderId}:total_remaining` Key原子读取，避免逐桶GET的不精确问题
- **锁定量决策**：新lockOrder的lockQuantity = `store.auto-lock.quantity`（默认10000），或基于历史扣减速率动态计算
- **部分锁定**：当 `sq - lq < lockQuantity` 但 `sq - lq >= store.auto-lock.min-lock-quantity` 时，自动调整为 `sq - lq` 作为实际锁定量
- **活跃lockOrder数量控制**：同一SKU同时最多存在 `store.auto-lock.max-active`（默认2）个ACTIVE状态的lockOrder。创建lockOrder时使用分布式锁（key=`auto-lock-create:{skuId}`）串行化同一SKU的锁库存创建操作，锁持有时间覆盖整个锁库存操作（Step 0 + Step 1 + Step 2 + Step 3），确保检查和创建的原子性。同时在锁库存DB事务内（Step 2）INSERT之前执行 `SELECT COUNT(*) FROM lock_inventory_order WHERE sku_id = #{skuId} AND status = 'ACTIVE' FOR UPDATE`，如果数量已达max-active则回滚事务并清理Redis
- **自动锁库存与手动锁库存兼容**：手动锁库存创建的lockOrder同样纳入活跃路由管理
- **预留DB降级额度**：自动锁库存时保留一定比例的可用额度给DB降级路径，配置项 `store.auto-lock.reserve-ratio`（默认0.1），即 `actualLockQuantity = min(lockQuantity, (sq - lq) * (1 - reserve-ratio))`

#### Scenario: 热点品自动触发锁库存

- **GIVEN** 商品A被热点识别系统标记为热点品
- **WHEN** 第一笔扣减请求到达（无论走Redis还是DB降级）
- **THEN** 系统自动为商品A创建lockOrder-A（lockQuantity=10000，16桶），写入活跃路由缓存
- **AND** 后续扣减请求路由到lockOrder-A的Redis分桶

#### Scenario: 滚动创建新lockOrder

- **GIVEN** 商品A的lockOrder-A分桶总余量降至50%以下
- **WHEN** 自动锁库存模块检测到余量阈值触发（通过 `total_remaining` Key检测）
- **THEN** 系统按严格时序提前创建lockOrder-B（lockQuantity=10000，16桶）
- **AND** 在lockOrder-B的Redis分桶初始化完成、DB lq更新完成、lockOrder记录插入完成后，原子更新活跃路由缓存 `inventory:active_lock:{skuId}` 指向lockOrder-B
- **AND** lockOrder-A仍可继续扣减（直到合并提交时失效）

#### Scenario: 无可用库存时自动锁库存失败

- **GIVEN** 商品A的 `sq - lq = 0`（无可用额度）
- **WHEN** 自动锁库存模块尝试创建新lockOrder
- **THEN** DB UPDATE影响行数为0，返回 `LOCK_QUANTITY_EXCEEDED`
- **AND** 不创建Redis分桶，不更新活跃路由缓存
- **AND** 后续扣减请求走DB降级路径

### Requirement: 活跃lockOrder路由机制 (Active Lock Order Routing)

系统 SHALL 提供活跃lockOrder路由能力，使扣减请求自动路由到当前可用的lockOrder，无需调用方感知lockOrderId。

#### 核心设计

- **路由缓存**：维护 `inventory:active_lock:{skuId}` → `lockOrderId` 的Redis映射，扣减请求通过skuId自动获取当前活跃的lockOrderId
- **原子切换**：新lockOrder创建后（Redis分桶初始化完成、DB lq更新完成、lockOrder记录插入完成后），通过Redis SET原子更新路由缓存，旧lockOrder的合并提交不影响新lockOrder的扣减
- **历史路由兜底**：当活跃路由对应的lockOrder分桶索引已失效时，查询历史路由列表尝试旧lockOrder，减少降级到DB路径的少卖风险
- **路由降级**：若所有活跃lockOrder均不可用，扣减请求降级走DB直接扣减

#### 路由数据结构

> **Redis Key设计规范（Redis Cluster兼容）**：所有Redis Key使用Hash Tag语法 `{...}` 确保同一实体的相关Key分布在同一hash slot，兼容Redis Cluster模式。Hash Tag内为实体标识（lockOrderId或skuId），决定Key的hash slot归属。以下为Key格式对照：
>
> | 逻辑Key | 实际Key格式 | Hash Tag | 说明 |
> |---------|------------|----------|------|
> | `inventory:lock:{lockOrderId}:bucket:{n}` | `inventory:{lockOrderId}:lock:bucket:{n}` | `{lockOrderId}` | 同一lockOrder的所有桶在同一slot |
> | `inventory:lock:{lockOrderId}:meta` | `inventory:{lockOrderId}:lock:meta` | `{lockOrderId}` | 与桶Key同slot，Lua脚本需要 |
> | `inventory:lock:{lockOrderId}:total_remaining` | `inventory:{lockOrderId}:lock:total_remaining` | `{lockOrderId}` | 与桶Key同slot，Lua脚本需要 |
> | `inventory:lock:{lockOrderId}:deduct_qps:{window}` | `inventory:{lockOrderId}:lock:deduct_qps:{window}` | `{lockOrderId}` | 与桶Key同slot |
> | `inventory:active_lock:{skuId}` | `inventory:{skuId}:active_lock` | `{skuId}` | 路由Key |
> | `inventory:active_lock_history:{skuId}` | `inventory:{skuId}:active_lock_history` | `{skuId}` | 与路由Key同slot，Lua原子更新需要 |
> | `inventory:auto_lock_pending:{skuId}` | `inventory:{skuId}:auto_lock_pending` | `{skuId}` | 与路由Key同slot |
> | `inventory:emergency_degrade:{skuId}` | `inventory:{skuId}:emergency_degrade` | `{skuId}` | 与路由Key同slot |
>
> 文档中为可读性使用逻辑Key格式，实际实现必须使用Hash Tag格式。若部署为Redis Standalone/Sentinel模式，Hash Tag不影响正确性（仅作为Key前缀的一部分）。

```
Redis Key: inventory:active_lock:{skuId}
Value: lockOrderId（当前活跃的锁库存单据ID）
TTL: 与锁库存单据过期时间一致

Redis Key: inventory:active_lock_history:{skuId}
Value: List[lockOrderId]（最近N个活跃的lockOrderId，用于兜底查询）
```

> **路由更新原子性保障**：活跃路由SET与历史列表APPEND必须原子执行，避免SET成功但APPEND失败导致兜底路由丢失。使用Redis Lua脚本封装为原子操作：
>
> ```lua
> -- KEYS[1] = inventory:active_lock:{skuId}
> -- KEYS[2] = inventory:active_lock_history:{skuId}
> -- ARGV[1] = lockOrderId
> -- ARGV[2] = TTL for active_lock key
> -- ARGV[3] = max history size (default 5)
> redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
> redis.call('RPUSH', KEYS[2], ARGV[1])
> local len = redis.call('LLEN', KEYS[2])
> local maxHistory = tonumber(ARGV[3] or '5')
> if len > maxHistory then
>     redis.call('LTRIM', KEYS[2], len - maxHistory, -1)
> end
> return 1
> ```

> **单活跃路由设计权衡**：当前设计活跃路由只指向最新lockOrder，旧lockOrder剩余库存不再被新请求路由到（只能通过历史路由兜底或等合并提交后释放到sq-lq）。这是简化路由逻辑的trade-off：旧lockOrder余量通常较少（触发新lockOrder创建时已降至50%以下），多路由增加扣减路径复杂度和延迟。如未来需充分利用旧lockOrder余量，可将路由缓存改为有序列表。

#### 历史路由遍历约束

- **最大遍历数量**：限制历史路由遍历最多 `store.routing.max-history-scan`（默认3）个lockOrder，避免遍历过多增加延迟
- **超时机制**：历史路由遍历总耗时不超过 `store.routing.history-scan-timeout-ms`（默认5ms），超时则直接降级DB
- **余量预检**：遍历时先检查 `inventory:lock:{lockOrderId}:total_remaining` Key，余量为0或Key不存在的lockOrder直接跳过
- **历史列表清理**：合并提交完成后，从 `inventory:active_lock_history:{skuId}` 中移除已ARCHIVED的lockOrderId，减少无效遍历。**异步化优化**：历史列表清理改为异步——合并提交完成后发送异步事件，由后台任务清理历史列表。即使清理延迟，遍历时余量预检（检查total_remaining）也能跳过无效lockOrder，不影响正确性
- **路由缓存主动清理**：合并提交完成后，若无新的ACTIVE lockOrder，主动删除 `inventory:active_lock:{skuId}` 路由缓存，避免后续扣减请求先查询到已ARCHIVED的lockOrder再降级，减少一次无效Redis GET操作（约0.5ms）。若有新的ACTIVE lockOrder，路由缓存已在创建新lockOrder时更新，无需额外操作

#### 扣减接口变更

原接口 `Controller.deduct(orderId, lockOrderId, skuId, quantity)` 中 `lockOrderId` 参数改为**可选**：

- **传入lockOrderId**：直接使用指定的lockOrder（兼容手动锁库存场景）
- **不传lockOrderId**：通过路由缓存自动获取当前活跃lockOrderId

#### Scenario: 自动路由到活跃lockOrder

- **WHEN** 扣减请求未指定lockOrderId
- **THEN** 系统查询 `inventory:active_lock:{skuId}` 获取当前活跃lockOrderId
- **AND** 检查该lockOrderId的分桶索引是否有效
- **AND** 有效则走Redis分桶扣减路径，无效则降级走DB

#### Scenario: 活跃lockOrder切换（原子更新）

- **WHEN** 自动锁库存模块创建新lockOrder-B（且Redis分桶初始化完成、DB lq更新完成、lockOrder记录插入完成）
- **THEN** 系统使用Lua脚本原子执行：SET `inventory:active_lock:{skuId} = lockOrder-B` + RPUSH `inventory:active_lock_history:{skuId}` 追加lockOrder-B
- **AND** 新的扣减请求自动路由到lockOrder-B
- **AND** lockOrder-A的合并提交可安全进行（分桶索引独立，互不影响）

#### Scenario: 路由缓存失效兜底

- **GIVEN** `inventory:active_lock:{skuId}` 缓存不存在（如Redis重启后丢失）
- **WHEN** 扣减请求到达
- **THEN** 系统查询DB：`SELECT id FROM lock_inventory_order WHERE sku_id = #{skuId} AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1`
- **AND** 若找到ACTIVE状态的lockOrder，重建路由缓存并继续扣减
- **AND** 若无ACTIVE状态的lockOrder，降级走DB直接扣减

#### Scenario: 活跃路由lockOrder分桶索引失效，历史路由兜底

- **GIVEN** 活跃路由指向lockOrder-B，但lockOrder-B的分桶索引已失效（正在合并提交或已完成合并）
- **WHEN** 扣减请求到达
- **THEN** 系统查询 `inventory:active_lock_history:{skuId}` 获取历史lockOrder列表
- **AND** 按创建时间倒序遍历（最多 `store.routing.max-history-scan` 个，总耗时不超过 `store.routing.history-scan-timeout-ms`）
- **AND** 遍历时先检查 `total_remaining` Key，余量为0的直接跳过
- **AND** 第一个有效且有余量的lockOrder用于扣减
- **AND** 全部无效或超时则降级走DB直接扣减

### Requirement: Redis分桶扣减模块 (Bucket Deduction)

系统 SHALL 实现基于Redis分桶的高并发库存预扣减能力。

#### 分桶策略
- 采用 **per-lockOrder 分桶设计**：每次锁库存操作拥有独立的N个Redis分桶，Key格式 `inventory:lock:{lockOrderId}:bucket:{n}`，同一SKU的不同lockOrder互不干扰
- 将锁定的库存均匀分配到N个Redis分桶（N可配置，默认16，参见分桶数指导原则）
- 扣减路由采用**随机选择 + 单桶耗尽fallover**策略，从该lockOrder的N个桶中随机选择一个执行DECR
- 维护分桶索引缓存（`inventory:lock:{lockOrderId}:meta`），存储当前分桶数量、skuId、各桶Key等信息
- 维护总余量Key（`inventory:lock:{lockOrderId}:total_remaining`），记录当前lockOrder的分桶总余量，用于余量阈值检测和历史路由余量预检。每次Lua扣减/INCR回补时同步DECRBY/INCRBY该Key，保证与桶计数原子一致

#### 扣减流程

1. **WHEN** 用户发起下单请求
2. **THEN** 系统从该lockOrder的N个分桶中随机选择一个，执行Lua脚本原子扣减
3. **IF** Lua脚本返回1（成功）或返回2（成功且分桶耗尽）
4. **THEN** 继续往DB插入库存扣减明细记录；若返回2，同时异步触发该lockOrder的合并提交
5. **IF** Lua脚本返回当前桶余量不足（返回0），**THEN** fallover到其他桶重试（最多重试M次，默认3次）
6. **IF** 所有桶均不足或Redis超时/异常，**THEN** 降级走传统DB直接扣减流程

#### Lua脚本扣减（防止DECR后计数器变负，同步更新total_remaining，分桶耗尽返回2触发合并）

```lua
-- KEYS[1] = bucket key
-- KEYS[2] = total_remaining key
-- ARGV[1] = deduct quantity
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local total = tonumber(redis.call('GET', KEYS[2]) or '0')
local quantity = tonumber(ARGV[1])
if current >= quantity and total >= quantity then
    redis.call('DECRBY', KEYS[1], quantity)
    local remaining = redis.call('DECRBY', KEYS[2], quantity)
    if tonumber(remaining) <= 0 then
        return 2  -- success + 分桶耗尽，触发合并提交
    end
    return 1  -- success
else
    return 0  -- insufficient
end
```

> **total_remaining防御性检查**：Lua脚本同时检查 `current >= quantity` 和 `total >= quantity`，防止因数据不一致导致total_remaining变负。正常情况下 `total >= current`，此检查为防御性编程。
>
> **返回值语义**：0=余量不足，1=扣减成功，2=扣减成功且分桶耗尽（应用层收到返回值2时异步触发该lockOrder的合并提交）。

> **单桶扣减限制**：当前版本Lua扣减脚本要求单桶余量 >= 扣减数量（`current >= quantity`）。当quantity > 1且单桶余量不足但总余量充足时（如16桶各5件，购买10件），系统会降级走DB路径。**建议单桶初始容量 >= 常见最大购买数量**（如 `actualLockQuantity / N >= 10`）。中期可增强Lua脚本支持跨桶扣减。

#### Lua脚本原子初始化分桶（防止部分桶初始化导致lq与Redis可用量不一致）

```lua
-- KEYS[1..N] = bucket keys (inventory:lock:{lockOrderId}:bucket:0..N-1)
-- KEYS[N+1] = meta key (inventory:lock:{lockOrderId}:meta)
-- KEYS[N+2] = total_remaining key (inventory:lock:{lockOrderId}:total_remaining)
-- ARGV[1..N] = bucket initial values
-- ARGV[N+1] = meta value (JSON: bucket count, skuId, bucket key pattern)
-- ARGV[N+2] = total_remaining initial value
for i = 1, #KEYS - 2 do
    redis.call('SET', KEYS[i], ARGV[i])
end
redis.call('SET', KEYS[#KEYS - 1], ARGV[#KEYS - 1])
redis.call('SET', KEYS[#KEYS], ARGV[#KEYS])
return #KEYS - 2  -- 返回初始化的桶数量
```

#### Lua脚本原子清理分桶（DB锁库存失败时回滚Redis）

```lua
-- KEYS[1..N] = bucket keys
-- KEYS[N+1] = meta key
-- KEYS[N+2] = total_remaining key
for i = 1, #KEYS do
    redis.call('DEL', KEYS[i])
end
return 1
```

#### Lua脚本原子条件INCR回补（PENDING取消时回补桶计数和total_remaining）

```lua
-- KEYS[1] = meta key (inventory:lock:{lockOrderId}:meta)
-- KEYS[2] = bucket key (inventory:lock:{lockOrderId}:bucket:{n})
-- KEYS[3] = total_remaining key
-- ARGV[1] = refund quantity
-- 原子操作：检查meta有效性 + INCR回补在同一脚本内完成，避免meta检查与INCR执行之间的时间窗口
local metaExists = redis.call('EXISTS', KEYS[1])
if tonumber(metaExists) == 1 then
    redis.call('INCRBY', KEYS[2], ARGV[1])
    redis.call('INCRBY', KEYS[3], ARGV[1])
    return 1  -- INCR回补成功
else
    return 0  -- meta已失效，跳过INCR
end
```

> **bucket_index有效性校验**：应用层构造Lua脚本KEYS前，必须校验bucket_index有效性（`0 <= bucketIndex < bucketCount`）。若bucket_index超出有效范围（因bug等），跳过INCR回补并记录告警，避免INCR作用于不存在的Key创建错误计数器。

#### Scenario: 正常扣减流程（合并下单明细）

- **GIVEN** 商品A已锁定1000库存到Redis（16桶，每桶62或63）
- **WHEN** 用户购买10件商品A
- **AND** 随机选择的分桶当前余量充足
- **THEN** Lua脚本原子扣减成功（桶计数和total_remaining同步减少），DB插入一条**合并下单明细**（扣减路径=MERGE\_BUCKETS，状态=PENDING，lock\_order\_id=当前锁库存单据ID，bucket\_index=实际扣减的桶编号）
- **AND** 返回扣减成功

#### Scenario: 单桶耗尽fallover

- **GIVEN** 商品A的bucket\[3]已耗尽，但其他桶仍有余量
- **WHEN** 随机选择到bucket\[3]执行扣减
- **THEN** Lua脚本返回0（余量不足）
- **AND** 系统自动fallover到其他桶重试
- **AND** 其他桶扣减成功后正常插入DB明细

#### Scenario: Redis异常降级（普通下单明细）

- **GIVEN** Redis服务出现超时或不可用
- **WHEN** 用户发起扣减请求
- **THEN** 系统自动降级为DB直接扣减模式
- **AND** 在DB事务内原子执行：UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity} WHERE id = #{skuId} AND sq - lq >= #{quantity}
- **AND** 同时插入一条**普通下单明细**（扣减路径=DIRECT\_DB，状态=MERGED，lock\_order\_id=NULL）
- **AND** 若DB扣减失败（sq-lq可用额度不足），返回错误码 `INSUFFICIENT_STOCK`
- **AND** 保证业务可用性（可能牺牲部分性能）

#### Scenario: Redis DECR后库存不足（无需INCR回滚）

- **GIVEN** Lua脚本已在原子操作内检查余量，不会出现"先减后加"的问题
- **WHEN** 桶内余量不足时
- **THEN** Lua脚本直接返回0，不修改Redis计数器，无需回滚

### Requirement: 数据模型设计 (Data Model Design)

系统 SHALL 采用独立模型分别存储锁库存单据、扣减明细和回补明细，作为最终扣减成功的唯一依据。

#### 模型拆分设计决策

本系统存在四种明细概念（锁库存单据、合并下单明细、普通下单明细、回补明细），它们在数据模型上**拆分为三张独立表**，而非统一存储于单表。

**为什么拆分为独立模型？**

四种明细的字段差异显著，大量字段只对某一种明细有意义，对其他明细为NULL：

| 字段 | 锁库存单据 | 合并下单明细 | 普通下单明细 | 回补明细 |
|------|----------|------------|------------|---------|
| 关联订单ID | ❌ | ✅必填 | ✅必填 | ✅ |
| lock_order_id | ❌(自己就是) | ✅必填 | ❌NULL | 同原明细 |
| bucket_index | ❌ | ✅必填 | ❌NULL | 同原明细 |
| merge_batch_id | ❌ | ✅ | ❌ | ❌ |
| ref_detail_id | ❌ | ❌ | ❌ | ✅(关联原明细) |
| Redis分桶信息 | ✅ | ❌ | ❌ | ❌ |
| 过期时间 | ✅ | ❌ | ❌ | ❌ |
| 状态机 | ❌(两阶段) | ✅(五状态) | ✅(四状态) | ❌(创建即生效) |

拆分后的优势：
- **字段清晰度**：每张表字段都是必填，语义明确，无大量NULL字段
- **数据完整性**：每张表独立NOT NULL约束，强类型保证
- **合并提交扫描效率**：直接扫deduction_detail，天然只含扣减明细，无需额外过滤
- **索引效率**：每张表索引精简，针对性强，避免单表索引膨胀
- **领域边界**：每个模型对应一个领域概念，职责清晰

**合并下单明细和普通下单明细为什么不拆？**

它们的字段结构几乎一致（唯一差异是 `lock_order_id` 和 `merge_batch_id` 是否为NULL，以及初始状态不同），共享同一套状态机（MERGED之后完全一致），放在同一张表通过 `deduct_path` 区分是合理的。

**模型拆分方案：**

| 模型 | 表名 | 包含的明细类型 | 说明 |
|------|------|-------------|------|
| 锁库存单据 | lock\_inventory\_order | 锁库存单据 | 父单据，记录锁库存操作，独立生命周期 |
| 扣减明细 | deduction\_detail | 合并下单明细 + 普通下单明细 | 子单据/独立单据，共享状态机 |
| 回补明细 | refund\_detail | 回补明细 | 关联单据，记录回补快照 |

#### 锁库存单据模型 (lock_inventory_order)

##### 字段定义

- 单据ID / lockOrderId（全局唯一，主键）
- 商品ID/SKU
- 锁定数量（lock\_quantity，lq变更量，**不可变字段**：创建后不可UPDATE，合并提交时通过 `SELECT lock_quantity` 读取用于lq减量更新，修改lockQuantity会导致lq减量错误）
- Redis分桶信息（bucket\_info，关联的Redis分桶Key列表和分桶数量）
- 过期时间（expire\_time，锁库存单据的有效期限）
- 状态（status：ACTIVE / ARCHIVED）
- 幂等键（idempotent\_key，唯一索引，用于锁库存操作幂等保障）
- 合并完成标记（merge\_completed，BOOLEAN，默认false，标记合并提交后Redis分桶是否已清理完成）
- 创建时间戳

##### 索引定义

- PRIMARY KEY (id)
- UNIQUE KEY uk_idempotent_key (idempotent_key)
- INDEX idx_sku_status (sku_id, status)

##### 生命周期

锁库存单据的生命周期分为两个阶段：

```
ACTIVE（活跃期）
  ├── 创建：锁库存操作时，lq增加，Redis分桶初始化
  ├── 职责：接受新的合并下单明细扣减，作为扣减屏障的判断依据
  └── 退出条件：合并提交完成（lq减去当前lockOrder的lockQuantity，Redis分桶清除）
       │
       ▼
ARCHIVED（归档期）
  ├── 进入：合并提交完成后自动进入
  ├── 职责：供子单据关联查询、库存回收、对账审计
  └── 退出条件：关联的所有合并下单明细均到达终态（CANCELLED/REFUNDED），且无待处理的回收操作
```

> **为什么锁库存单据在合并提交后不立即结束？**
> 1. **库存回收**：合并提交后仍可能需要回收未消耗的lq剩余量（如商家编辑库存为0时需先释放预锁库存）
> 2. **扣减屏障**：合并提交时需通过lockOrderId判断分桶索引是否有效，锁库存单据是屏障状态的查询依据
> 3. **子单据关联**：合并下单明细在MERGED/OCCUPIED状态下取消/退款时，通过lock\_order\_id关联锁库存单据追溯扣减上下文
> 4. **对账审计**：锁库存单据记录了"这次锁库存操作最终卖出了多少、回收了多少"的完整快照
> 5. **崩溃恢复**：`merge_completed` 标记用于检测合并提交后Redis分桶是否已清理完成，应用启动时扫描 `status='ARCHIVED' AND merge_completed=false` 的记录，补偿清理残留Redis分桶
>
> **ARCHIVED记录归档**：长期运行后 `lock_inventory_order` 表数据量持续增长，建议对 `status='ARCHIVED' AND merge_completed=true` 且所有子单据终态的记录定期归档到 `lock_inventory_order_archive` 表（或按 `created_at` 做按月分区，过期分区整体归档），超过保留期限（如90天）的ARCHIVED记录可安全删除

##### Scenario: 创建锁库存单据

- **WHEN** 业务方调用锁库存接口，传入商品ID、锁定数量和幂等键
- **THEN** 系统在lock\_inventory\_order表插入一条记录（status=ACTIVE，记录lq变更量、关联的Redis分桶信息、idempotent\_key、merge\_completed=false）
- **AND** 返回lockOrderId用于后续扣减关联

##### Scenario: 锁库存单据进入归档期

- **WHEN** 合并提交完成（lq减去当前lockOrder的lockQuantity，Redis分桶清除）
- **THEN** 锁库存单据状态从ACTIVE更新为ARCHIVED
- **AND** 该单据不再接受新的合并下单明细扣减

#### 扣减明细模型 (deduction_detail)

##### 字段定义

- 单据ID（全局唯一，主键）
- 商品ID/SKU
- 扣减数量
- 扣减路径（deduct\_path：MERGE\_BUCKETS / DIRECT\_DB）：区分"合并下单明细"和"普通下单明细"
- 桶标识（bucket\_index：整数，MERGE\_BUCKETS路径必填，DIRECT\_DB路径为NULL）：记录扣减发生的具体Redis桶编号，用于INCR回补时精确恢复对应分桶计数
- 状态（status：PENDING / MERGED / OCCUPIED / CANCELLED / REFUNDED）
- 创建时间戳
- 关联订单ID（order\_id，**必填**，用于幂等和回补关联）
- 关联锁库存单据ID（lock\_order\_id，外键关联lock\_inventory\_order，MERGE\_BUCKETS路径必填，DIRECT\_DB路径为NULL）
- 合并批次ID（merge\_batch\_id，合并时填充，用于幂等防护）

##### 索引定义

- PRIMARY KEY (id)
- **UNIQUE KEY uk_order_sku (order_id, sku_id)**：扣减幂等硬约束，同一订单同一SKU只能有一条扣减明细，防止重试导致重复扣减
- INDEX idx_lock_order_status (lock_order_id, status)
- INDEX idx_merge_batch (merge_batch_id)

##### 明细分类

| 明细类型 | 扣减路径 | 说明 | 状态起点 |
|----------|----------|------|----------|
| **合并下单明细** | MERGE\_BUCKETS | 走Redis分桶预扣减的明细，需经合并提交才能落DB库存 | PENDING |
| **普通下单明细** | DIRECT\_DB | 走DB直接扣减的明细（低并发或Redis降级），立即变更sq/wq | MERGED |

##### 锁库存单据与扣减明细的关系

- 锁库存单据是**父单据**（记录哪次锁库存操作锁了多少lq、关联哪些Redis分桶）
- 合并下单明细是**子单据**（通过 lock\_order\_id 外键关联父单据，记录每次具体扣减）
- 合并提交按父单据维度聚合所有子单据，计算净扣减值

##### 明细状态机

```
【合并下单明细路径】(扣减路径=MERGE_BUCKETS)
PENDING(待合并) ──合并提交──▶ MERGED(已合并) ──付款确认──▶ OCCUPIED(已占用) ──退款──▶ REFUNDED(已回补)
      │                          │
      └──取消(付款前)──▶ CANCELLED(已取消)
                            │
                            └──取消(付款前)──▶ CANCELLED(已取消，需回补wq→sq)

【普通下单明细路径】(扣减路径=DIRECT_DB)
MERGED(已合并) ──付款确认──▶ OCCUPIED(已占用) ──退款──▶ REFUNDED(已回补)
      │
      └──取消(付款前)──▶ CANCELLED(已取消，需回补wq→sq)
```

##### 状态转换规则

| 当前状态     | 触发事件    | 适用路径          | 目标状态      | DB库存操作          | Redis操作 |
| -------- | ------- | ------------- | --------- | ------------- | -------- |
| PENDING  | 合并提交    | MERGE\_BUCKETS | MERGED    | sq减少，wq增加     | 分桶清除（合并提交统一处理） |
| PENDING  | 取消（付款前） | MERGE\_BUCKETS | CANCELLED | 无需DB库存操作（未合并） | **原子条件INCR回补**：Lua脚本内检查分桶索引有效性，有效则回补bucket\_index对应分桶计数和total_remaining，无效则跳过 |
| MERGED   | 付款确认    | 两条路径          | OCCUPIED  | wq减少，oq增加（WHERE wq>=qty）     | 无 |
| MERGED   | 取消（付款前） | 两条路径          | CANCELLED | wq减少，sq增加（WHERE wq>=qty） | 无 |
| OCCUPIED | 退款      | 两条路径          | REFUNDED  | oq减少，sq增加（WHERE oq>=qty） | 无 |

> **关键差异1**：合并下单明细初始状态为 PENDING（Redis预扣减仅修改计数器，DB库存尚未变更），需经合并提交才进入 MERGED；普通下单明细初始状态直接为 MERGED（DB直接扣减时已同时完成 sq→wq 转移，无需合并步骤）。
>
> **关键差异2**：PENDING状态取消时，虽然DB库存无需操作，但**必须条件INCR回补Redis对应分桶计数**，否则该lockOrder下的分桶余量永久偏低，引发少卖。INCR回补前必须先检查分桶索引缓存（meta）是否仍然有效，若已失效（lockOrder正在合并提交或已完成合并）则跳过INCR（桶即将或已被清除，INCR无意义且可能产生竞态问题）。**条件INCR回补必须使用Lua脚本原子执行**（检查meta有效性 + INCR回补在同一脚本内完成），避免meta检查与INCR执行之间的时间窗口导致INCR作用于即将被清除的分桶。Lua脚本定义见"Redis分桶扣减模块 → Lua脚本原子条件INCR回补"。
>
> MERGED及之后状态取消/退款时，Redis分桶已清除，回补操作仅在DB层面进行。

##### Scenario: 插入合并下单明细（Redis预扣减路径）

- **WHEN** Redis Lua脚本预扣减成功后
- **THEN** 系统向deduction\_detail表插入一条扣减明细记录（扣减路径=MERGE\_BUCKETS，状态=PENDING，lock\_order\_id=当前锁库存单据ID，bucket\_index=实际扣减的桶编号）
- **AND** 若INSERT唯一索引冲突（说明是重试且上次实际成功），INCR回补本次Lua扣减的数量到对应分桶计数和total\_remaining，返回成功（幂等由DB唯一索引最终保障）
- **AND** 只有明细插入成功才视为本次扣减成功
- **AND** 若明细插入失败（唯一索引冲突除外），需触发Redis回补机制（INCR恢复bucket\_index对应的分桶计数和total_remaining）

> **幂等检查统一时序**：扣减请求统一为**先幂等检查后Lua扣减**（高效路径）——先SELECT检查(order_id, sku_id)是否已存在，已存在则直接返回（Lua未执行，无需INCR）；不存在则执行Lua扣减+DB INSERT。DB唯一索引作为最终幂等保障（防御路径）——INSERT冲突时INCR回补本次Lua扣减数量。
>
> **极致性能路径（可选优化）**：对于首次请求占绝大多数的场景，可省略前置SELECT，直接执行Lua扣减+DB INSERT，捕获DuplicateKeyException后INCR回补。省去首次请求的SELECT查询（约1ms DB延迟），10K TPS下每秒减少10000次SELECT。权衡：重试场景多执行一次Lua扣减+INCR回补（2次Redis操作 vs 1次DB SELECT），但重试是低频场景，总体收益为正。

##### Scenario: 插入普通下单明细（DB直接扣减路径）

- **WHEN** Redis全部桶不足或Redis超时/异常，系统降级走DB直接扣减
- **THEN** 系统在DB事务内原子执行：UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity} WHERE id = #{skuId} AND sq - lq >= #{quantity}
- **AND** 同时向deduction\_detail表插入一条扣减明细记录（扣减路径=DIRECT\_DB，状态=MERGED，lock\_order\_id=NULL）
- **AND** 幂等检查在扣减请求入口统一执行（先于路由解析），DB降级路径同样受 `(order_id, sku_id)` 唯一索引保障
- **AND** 若DB扣减失败（sq-lq可用额度不足），返回错误码 `INSUFFICIENT_STOCK`

##### Scenario: PENDING状态取消（合并提交前取消）

- **WHEN** 合并下单明细处于PENDING状态时，用户取消订单
- **THEN** 系统检查明细当前状态：
  - **IF** 明细仍为PENDING：更新状态为CANCELLED，DB库存无需操作（sq/wq/lq均未变更），**原子条件INCR回补**：使用Lua脚本原子检查分桶索引缓存（`inventory:lock:{lockOrderId}:meta`）是否仍然有效，有效则INCR回补bucket\_index对应分桶计数和total\_remaining，已失效则跳过（Lua脚本内原子完成，无需额外操作）
  - **IF** 明细已被合并提交标记为MERGED（合并提交事务内"先标记后计算"获取了行锁，CANCEL操作被阻塞直到事务提交后）：走MERGED状态取消路径（wq回补sq），Redis无需操作（分桶已在合并提交时清除）
- **AND** 此设计确保PENDING取消与合并提交的竞态安全：合并提交事务内UPDATE获取行锁阻止并发CANCEL，CANCEL操作要么在合并前完成（走PENDING取消路径），要么在合并后执行（走MERGED取消路径）

##### Scenario: MERGED状态取消（合并提交后取消）

- **WHEN** 合并下单明细或普通下单明细处于MERGED状态时，用户取消订单
- **THEN** 向refund\_detail表插入一条回补明细（关联原扣减明细）
- **AND** 明细状态更新为CANCELLED
- **AND** DB事务内原子执行：UPDATE inventory SET wq = wq - #{quantity}, sq = sq + #{quantity} WHERE id = #{skuId} AND wq >= #{quantity}
- **AND** 若UPDATE影响行数为0（wq不足），触发告警，进入人工处理流程
- **AND** Redis无需操作（分桶已在合并提交时清除）

#### 回补明细模型 (refund_detail)

##### 字段定义

- 单据ID（全局唯一，主键）
- 商品ID/SKU
- 回补数量（refund\_quantity）
- 扣减路径（deduct\_path：同原明细，MERGE\_BUCKETS / DIRECT\_DB）
- 状态（status：MERGED，创建即生效）
- 创建时间戳
- 关联订单ID（order\_id）
- 关联原扣减明细ID（ref\_detail\_id，外键关联deduction\_detail）
- 退款请求标识（refund\_request\_id，**业务级幂等键**：由调用方（如支付系统）传入，同一退款请求的唯一标识，解决不同重试请求生成不同单据ID导致重复退款的问题。为NULL时退化为仅依赖主键幂等）

##### 索引定义

- PRIMARY KEY (id)
- **UNIQUE KEY uk_ref_detail_request (ref\_detail\_id, refund\_request\_id)**：业务级退款幂等约束，同一扣减明细的同一退款请求只能有一条回补记录，防止支付系统回调重试（不同单据ID但同一退款请求）导致重复退款。**注意**：MySQL InnoDB中NULL值不参与唯一约束比较，即 `(ref_detail_id=1, refund_request_id=NULL)` 和 `(ref_detail_id=1, refund_request_id=NULL)` 可同时存在。因此当 `refund_request_id` 为NULL时，此唯一索引不提供去重保护，退化为仅依赖主键幂等。**建议调用方始终传入refund_request_id**以获得完整的业务级幂等保障

##### 为什么需要独立的回补明细模型？

**方案对比**：不使用回补明细，在原扣减明细上通过状态+回补字段表达 vs 使用独立回补明细

| 场景 | 无回补明细（在原明细上加字段） | 独立回补明细 |
|------|---------------------------|-----------|
| 全额退款 | ✅ 简单，UPDATE原明细即可 | 多一次INSERT |
| **部分退款** | ❌ 状态机膨胀（需PARTIAL\_REFUNDED状态）+ 时间线丢失（覆盖refund\_time） | ✅ 每次退款独立记录，天然支持 |
| **退款幂等** | ⚠️ 靠状态判断，退款处理中与退款成功难以区分 | ✅ 主键幂等，INSERT主键冲突即跳过 |
| **合并提交净扣减计算** | ⚠️ PENDING取消场景下，CANCELLED记录被WHERE条件遗漏，净扣减值偏大 | ✅ MERGED/OCCUPIED取消/退款时回补明细参与SUM计算，净扣减值准确（PENDING取消通过Redis INCR回补保障，不走refund_detail） |
| **快照原则** | ❌ 修改原有快照，违背"快照不可变" | ✅ 退款/取消也是扣减事件（反向），按快照原则应有独立记录 |

**结论**：以下四个理由使回补明细成为必须：
1. **部分退款支持**：电商常见场景（买10件退3件），独立回补明细天然支持多次部分退款，每次退款有独立记录和时间线
2. **退款幂等可靠性**：支付系统回调重试场景，回补明细ID作为主键天然幂等，比状态判断更可靠
3. **合并提交净扣减计算**：MERGED/OCCUPIED状态取消/退款时，回补明细参与 `SUM(扣减数量) - SUM(回补数量)` 计算，保证净扣减值准确；PENDING状态取消通过Redis INCR回补保障，不创建refund_detail
4. **快照不可变原则**：库存扣减明细是扣减快照，退款/取消是反向扣减事件，按快照原则应有独立记录，不应修改原有快照

##### Scenario: 插入回补明细

- **WHEN** 已扣减的明细发生取消或退款
- **THEN** 系统向refund\_detail表插入一条回补明细记录（扣减路径同原明细，关联原始扣减明细ID）
- **AND** 根据原明细状态执行对应的库存回补操作（MERGED→wq回补sq，OCCUPIED→oq回补sq）

#### 四种明细生命周期横向视图

```
锁库存单据:    创建(预热阶段,ACTIVE) ──合并提交──▶ 归档(ARCHIVED) ──所有子单据终态+无待处理回收──▶ 生命周期结束
合并下单明细: 创建(下单阶段,PENDING) → 合并提交(MERGED) → 付款确认(OCCUPIED) → 退款(REFUNDED) / 取消(CANCELLED)
普通下单明细: 创建(下单阶段,MERGED) ──→ 付款确认(OCCUPIED) → 退款(REFUNDED) / 取消(CANCELLED)
回补明细:    创建(回补阶段,MERGED) ──→ 关联原明细完成库存回补
```

> **关键说明**：锁库存单据拥有独立的两阶段生命周期（ACTIVE→ARCHIVED），不参与扣减明细的状态机流转。合并下单明细和普通下单明细共享 MERGED 之后的状态流转路径，差异在于到达 MERGED 状态的路径不同。回补明细创建即生效（MERGED），不参与状态机流转。

#### 明细的核心作用

1. **记录扣减信息**：下游系统（交易/支付）无需关心具体回补数量，库存内部通过明细恢复
2. **幂等性保障**：同一单据重复调用时，通过单据ID去重；同一订单同一SKU重复扣减时，通过 `(order_id, sku_id)` 唯一索引去重
3. **生命周期管理**：支撑扣减→合并→付款→取消/退款等完整状态流转
4. **合并幂等防护**：通过merge\_batch\_id字段防止同一明细被重复合并

### Requirement: 合并提交模块 (Merge Commit)

系统 SHALL 提供异步批量合并提交能力，将Redis分散扣减结果定期汇总到DB。

#### 合并策略
- **延迟触发**：热点下单延迟1秒后启动扫描（合并窗口期 `store.merge.delay-ms` 可配置，默认1000ms）
- **分桶耗尽触发**：当某lockOrder的所有分桶余量为0时，立即触发合并提交（避免无谓等待）。检测机制：增强Lua扣减脚本，当total_remaining减至0时返回特殊标识（返回值2），应用层收到返回值2时异步触发该lockOrder的合并提交
- **活跃度衰减触发**：当某lockOrder的扣减QPS低于阈值（`store.merge.idle-qps-threshold`，默认100/s）时，提前合并释放lq（适用于流量回落场景）。QPS测量采用**滑动窗口计数器**：每次扣减请求成功后INCR `inventory:lock:{lockOrderId}:deduct_qps:{second_window}`（Key含秒级时间窗口，TTL=2s自动过期），合并调度器每秒读取当前窗口的计数值，低于阈值则触发提前合并
- **批量处理**：按锁库存单据维度聚合待合并明细，计算净扣减数量
- **原子提交**：在事务内一次性完成DB库存字段更新和明细状态变更
- **lq减量更新**：合并提交完成后将lq减去当前lockOrder的lockQuantity（而非重置为0），支持多lockOrder并存场景。未卖出的库存自然保留在sq中
- **分布式锁维度为lockOrderId而非skuId**：合并操作的SQL作用域是 `WHERE lock_order_id = #{lockOrderId}`，锁维度应与操作作用域一致；同一SKU可存在多个并发lockOrder（per-lockOrder分桶隔离），用skuId做锁维度会不必要地阻塞不同单据的合并；lockOrderId粒度更精准，锁持有时间更短，死锁风险更低

#### 合并流程伪代码

```
1. 获取分布式锁（Redisson RLock，key=merge:{lockOrderId}）
2. 失效该lockOrder对应的Redis分桶索引缓存（inventory:lock:{lockOrderId}:meta）
   > 扣减屏障：作为性能优化手段减少穿透到事务内的请求数量，降低DB行锁竞争
   > 即使屏障有少量穿透，Step 4a-4b 的事务内先标记后计算机制可保证正确性
3. 分配全局唯一的merge_batch_id（前缀MERGE-{uuid}）
4. @Transactional事务内执行（先标记后计算，确保净扣减值与实际MERGED明细一致）：
   a. DB UPDATE deduction_detail:
      SET status='MERGED', merge_batch_id = #{batchId}
      WHERE lock_order_id = #{lockOrderId} AND status='PENDING' AND merge_batch_id IS NULL
      → 获取deduction_detail行锁，阻止并发CANCEL修改这些行的状态
      → 所有满足条件的PENDING明细（包括穿透窗口期间新插入的）被原子标记为MERGED
   a.5. IF Step 4a影响行数为0:
      → 无待合并明细（可能是重复触发），直接跳过Step 4b-4e，释放分布式锁，返回
      → 这是幂等保障的关键：二次合并触发时Step 4a影响0行，若继续执行Step 4d的lq减量更新会导致lq变负（lq已在首次合并中减量）
   b. DB SELECT COALESCE(SUM(quantity), 0) AS net_deduction
      FROM deduction_detail
      WHERE merge_batch_id = #{batchId}
      → 从Step 4a实际标记的明细计算净扣减值，与MERGED明细完全一致
      → 消除事务外预计算与事务内更新之间的时间窗口
      → COALESCE防止Step 4a影响0行时SUM返回NULL
   c. DB SELECT lock_quantity AS currentLockQuantity
      FROM lock_inventory_order
      WHERE id = #{lockOrderId}
      → 获取当前lockOrder的锁定量，用于lq减量更新
   d. DB UPDATE inventory:
      SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}, lq = lq - #{currentLockQuantity}
      WHERE id = #{skuId} AND sq >= #{net_deduction} AND lq >= #{currentLockQuantity}
      → lq减量更新：减去当前lockOrder的lockQuantity，而非重置为0
      → WHERE sq >= #{net_deduction} 作为最终防线，防止sq变负
      → WHERE lq >= #{currentLockQuantity} 防止lq变负（与sq/wq/oq非负约束一致）
      → 若UPDATE影响行数为0（sq不足或lq不足），事务回滚，触发告警，进入人工处理流程
   e. DB UPDATE lock_inventory_order:
      SET status='ARCHIVED' WHERE id = #{lockOrderId}
4.5 释放分布式锁   ← 提前释放：Step 5-6是幂等操作（重复DEL/UPDATE不影响正确性），无需持锁
5. 清零/删除该lockOrder对应的Redis分桶（含bucket keys、meta key、total_remaining key）
   → 幂等操作：重复DEL返回0，不影响正确性
6. 更新 lock_inventory_order SET merge_completed = true WHERE id = #{lockOrderId}
   → 幂等操作：重复UPDATE不影响正确性
```

> **为什么采用"先标记后计算"而非"先扫描后更新"？**
>
> 原方案（事务外扫描 → 事务内更新）存在两类竞态：
> - **竞态A**：新PENDING明细在扫描后插入 → 净扣减值未计入 → sq偏高 → 超卖
> - **竞态B**：PENDING明细在扫描后被取消 → 净扣减值仍包含已取消数量 → sq偏低 → 少卖
>
> "先标记后计算"方案通过以下机制同时解决两类竞态：
> - Step 4a 的 UPDATE 获取行锁，阻止并发 CANCEL 修改已标记行的状态 → 解决竞态B
> - Step 4b 从 Step 4a 实际标记的明细计算，即使有穿透窗口的 PENDING 明细被意外标记，也会被计入净扣减值 → 解决竞态A
> - 扣减屏障（Step 2）作为性能优化减少穿透量，但不是正确性的必要条件

> **为什么lq减量更新而非重置为0？**
>
> 同一SKU可同时存在多个ACTIVE状态的lockOrder（`store.auto-lock.max-active` 默认2），inventory表的lq字段是所有lockOrder的lockQuantity之和。如果合并提交时 `SET lq = 0`，会错误清除其他仍ACTIVE的lockOrder的lq份额，导致 `sq - lq` 虚高，DB降级路径可侵占其他lockOrder的Redis预锁库存，引发超卖。减量更新 `SET lq = lq - #{currentLockQuantity}` 确保每个lockOrder只清除自己的份额。

#### Scenario: 正常合并

- **GIVEN** 商品A有100条待合并明细（总扣减500件），lockOrder-A的lockQuantity=1000
- **WHEN** 合并任务触发
- **THEN** 商品A的sq减少500，wq增加500，lq减少1000（lockOrder-A的lockQuantity）
- **AND** 100条明细状态更新为"已合并"，填充merge\_batch\_id
- **AND** 对应Redis分桶清零/删除
- **AND** lockOrder-A的merge\_completed更新为true

#### Scenario: 多lockOrder并存时的合并

- **GIVEN** 商品A的lockOrder-A（lockQuantity=10000）和lockOrder-B（lockQuantity=10000）同时ACTIVE，inventory.lq=20000
- **WHEN** lockOrder-A合并提交，net\_deduction=7000
- **THEN** inventory: sq = sq - 7000, wq = wq + 7000, lq = lq - 10000 = 10000
- **AND** lockOrder-B仍然ACTIVE，lq=10000正确反映了lockOrder-B的锁定量
- **AND** sq - lq 仍然正确保护lockOrder-B的Redis预锁库存不被DB降级路径侵占

#### Scenario: 合并提交重复触发（幂等保障）

- **GIVEN** 合并任务已执行过一次
- **WHEN** 调度器再次触发同一lockOrderId的合并
- **THEN** 分布式锁保证同一时刻只有一个合并任务执行
- **AND** WHERE条件 `status='PENDING' AND merge_batch_id IS NULL` 过滤已合并记录
- **AND** 第二次合并查询不到待合并明细，直接跳过

#### Scenario: 合并提交DB更新部分失败

- **GIVEN** 合并提交事务内，sq/wq更新成功但明细状态更新失败
- **THEN** 整个事务回滚，sq/wq不变，明细仍为PENDING
- **AND** 下次合并任务重试时重新处理

#### Scenario: 合并提交sq不足（WHERE约束触发）

- **GIVEN** 合并提交计算net\_deduction=500，但当前sq=300
- **WHEN** 执行 `UPDATE inventory SET sq = sq - 500 ... WHERE sq >= 500`
- **THEN** UPDATE影响行数为0，事务回滚
- **AND** 触发告警，进入人工处理流程
- **AND** 此场景理论上不应发生（Redis Lua防超扣 + DB降级路径受sq-lq约束），作为最终防线

#### Scenario: 锁库存主动释放（复用合并提交）

- **GIVEN** 锁库存单据lockOrderId=123，lockQuantity=1000，已卖出300件
- **WHEN** 业务方调用释放接口
- **THEN** 触发合并提交流程：sq减少300，wq增加300，lq减少1000（lockQuantity）
- **AND** 剩余700件未卖出的库存自然保留在sq中

#### Scenario: 合并提交后孤立PENDING明细补偿

- **GIVEN** 合并提交事务已提交完成，lockOrder状态已更新为ARCHIVED
- **WHEN** 极端时序下，扣减请求在合并提交事务提交后、Redis桶清除前完成了Lua脚本和明细插入
- **THEN** 产生一条PENDING明细，但其父lockOrder已ARCHIVED，该明细无法通过正常合并提交流转
- **AND** 系统通过补偿扫描机制处理：定时任务查询 `SELECT d.* FROM deduction_detail d INNER JOIN lock_inventory_order l ON d.lock_order_id = l.id WHERE d.status = 'PENDING' AND l.status = 'ARCHIVED'`（JOIN优于IN子查询，大数据量下性能更优）
- **AND** 对孤立PENDING明细执行补偿合并（按lockOrderId维度加分布式锁 `compensate:{lockOrderId}`，串行处理）：
  - 获取分布式锁（key=compensate:{lockOrderId}）
  - 事务内"先标记后计算"：
    - UPDATE deduction_detail SET status='MERGED', merge_batch_id = #{compensateBatchId}
      WHERE lock_order_id = #{lockOrderId} AND status='PENDING' AND merge_batch_id IS NULL
    - SELECT COALESCE(SUM(quantity), 0) AS net_deduction FROM deduction_detail WHERE merge_batch_id = #{compensateBatchId}
    - UPDATE inventory SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}
      WHERE id = #{skuId} AND sq >= #{net_deduction}
      → 无需处理lq，lq已在原合并提交中减量更新
      → WHERE sq >= #{net_deduction} 防止sq变负
      → 若UPDATE影响行数为0，事务回滚，触发告警
  - 释放分布式锁
- **AND** 更新明细状态为MERGED，填充merge\_batch\_id标记为补偿合并（前缀COMP-{uuid}，与正常合并的MERGE-{uuid}命名空间隔离）

#### Scenario: 合并提交后应用崩溃恢复

- **GIVEN** 合并提交事务已提交（Step 4完成），但Redis分桶清理（Step 5）或merge_completed更新（Step 6）未完成时应用崩溃
- **WHEN** 应用重启
- **THEN** 启动时扫描 `lock_inventory_order WHERE status='ARCHIVED' AND merge_completed=false` 的记录
- **AND** 对每条记录补偿清理对应的Redis分桶（bucket keys、meta key、total_remaining key）
- **AND** 更新 merge_completed = true

### Requirement: 一致性保障机制 (Consistency Guarantee)

系统 SHALL 在分布式环境下保证库存数据的强一致性。

#### 核心原则

- **Redis仅作计数器**：防止超卖的手段，不是真实库存存储
- **DB明细是真相源**：最终扣减成功与否以DB明细存在为准
- **扣减屏障为性能优化**：合并提交时先失效分桶索引缓存（inventory:lock:{lockOrderId}:meta），减少穿透到事务内的请求数量，降低DB行锁竞争。屏障不是正确性的必要条件——即使屏障有少量穿透，事务内"先标记后计算"机制可保证净扣减值与实际MERGED明细一致，不会超卖
- **事务内先标记后计算为正确性保障**：合并提交事务内先UPDATE标记PENDING→MERGED（获取行锁阻止并发CANCEL），再从已标记明细SELECT SUM计算净扣减值，确保计算与更新原子一致
- **补偿机制完善**：针对各环节失败场景提供完整补偿
- **lq减量更新为多lockOrder并存保障**：合并提交时 `lq = lq - #{currentLockQuantity}` 确保每个lockOrder只清除自己的lq份额，避免错误清除其他仍ACTIVE的lockOrder的lq（详见"合并提交模块 → 为什么lq减量更新而非重置为0？"）
- **WHERE sq >= #{net_deduction} 为最终防线**：合并提交和补偿合并的SQL均增加此约束，防止sq变负

#### 异常场景处理

| 场景                       | 处理策略                                                               |
| ------------------------ | ------------------------------------------------------------------ |
| Redis Lua扣减成功，DB明细插入失败   | INCR回补Redis对应分桶库存和total_remaining（根据bucket\_index精确回补）                                                  |
| Redis扣减超时                | 当作失败处理，走DB降级                                                       |
| Redis DECR后余量不足（Lua脚本防负） | fallover到其他桶重试，全部不足则走DB降级                                          |
| PENDING状态取消（合并提交前取消）     | **原子条件INCR回补**：Lua脚本内检查分桶索引缓存(meta)有效性，有效则回补bucket\_index对应分桶计数和total_remaining；已失效则跳过INCR                              |
| 合并提交DB更新失败               | 事务回滚 + 重试机制 + 告警                                                   |
| 合并期间新扣减请求（同一lockOrder） | **扣减屏障拦截**（性能优化）：分桶索引已失效，降级走DB直接扣减路径（普通下单明细）；若屏障穿透，事务内"先标记后计算"机制保证正确性 |
| 合并期间新扣减请求（不同lockOrder） | 不受影响：per-lockOrder分桶隔离，各自独立扣减                                              |
| DB锁库存成功，Redis初始化失败       | 先Redis后DB策略降低失败概率 + Redis桶使用Lua脚本原子初始化（全部成功或全部回滚）+ 后台对账任务检测lq与Redis各桶sum不一致                       |
| DB锁库存事务失败（UPDATE成功INSERT失败） | DB事务回滚（UPDATE和INSERT在同一事务中）+ Lua脚本原子清理Redis分桶（使用预生成的lockOrderId构造Key） |
| 锁库存幂等冲突（并发请求同一idempotentKey） | 幂等键唯一索引去重 + 失败请求使用预生成lockOrderId执行Lua清理脚本回滚Redis分桶 |
| 锁库存调用超时重试               | 幂等键（idempotent_key）去重，返回已有lockOrderId，不重复执行锁库存操作 |
| 锁库存可售量不足                 | SQL条件 `sq - lq >= lockQuantity` 防超锁 + 部分锁定支持 + 错误码 `LOCK_QUANTITY_EXCEEDED` |
| 合并任务重复触发                 | 分布式锁 + merge\_batch\_id幂等防护                                        |
| 合并提交后应用崩溃（Redis分桶未清理）   | 启动时扫描 `status='ARCHIVED' AND merge_completed=false` 的记录，补偿清理Redis分桶 |
| Redis全锁定+Redis不可用        | 紧急解锁机制（详见下方"紧急降级方案"）                                               |
| 扣减明细重复插入（重试场景）           | `(order_id, sku_id)` 唯一索引硬约束，INSERT冲突时INCR回补本次Lua扣减数量 + 返回成功（幂等） |
| 取消/退款wq/oq不足             | SQL WHERE约束 `wq >= #{quantity}` / `oq >= #{quantity}` 防止字段变负，UPDATE影响行数为0时触发告警 |

#### 紧急降级方案

当Redis不可用且 `sq - lq = 0`（全部库存锁定到Redis）时，DB降级路径也无法扣减，系统完全不可用。紧急降级方案如下：

1. **紧急解锁接口**：提供 `emergencyUnlock(skuId)` 管理接口，对所有ACTIVE lockOrder逐个触发紧急合并提交（按lockOrderId维度加分布式锁串行处理），确保Redis分桶和lq同步释放。**禁止直接 `SET lq = 0`**：直接清零lq会移除DB降级路径对Redis预锁库存的保护屏障，若Redis部分恢复，Redis路径和DB降级路径可同时扣减同一批库存，导致超卖。如必须快速释放（合并提交耗时过长），应先使用Lua脚本批量清零所有ACTIVE lockOrder的Redis分桶，再SET lq=0，且在清零期间设置全局降级开关（`inventory:emergency_degrade:{skuId}` = true，TTL=30s），暂停Redis路径扣减，直到lq和Redis分桶同步处理完成。**SET lq=0后必须同步更新所有ACTIVE lockOrder状态为ARCHIVED**（`UPDATE lock_inventory_order SET status='ARCHIVED' WHERE sku_id = #{skuId} AND status = 'ACTIVE'`），否则系统处于不一致状态（lq=0但lockOrder仍ACTIVE，后续自动锁库存检查sq-lq会误判可用额度）
2. **预留DB降级额度**：自动锁库存时保留 `store.auto-lock.reserve-ratio`（默认0.1）的可用额度给DB降级路径，即 `actualLockQuantity = min(lockQuantity, (sq - lq) * (1 - reserve-ratio))`
3. **Redis不可用自动检测**：当Redis连续超时次数超过 `store.redis.fail-threshold`（默认5次），自动触发紧急合并提交，释放所有ACTIVE lockOrder的lq。超时计数采用**实例级共享**（`AtomicInteger redisFailCount`），任何线程遇到Redis超时即递增，成功即重置；当 `redisFailCount >= fail-threshold` 时，扫描所有有ACTIVE lockOrder的SKU，逐个触发紧急合并提交

#### 约束层级定义

- **SQL层硬约束**：`WHERE sq - lq >= #{lockQuantity}` 是最终防线，InnoDB行锁保证并发安全
- **SQL层最终防线**：`WHERE sq >= #{net_deduction}` 防止合并提交/补偿合并导致sq变负
- **SQL层字段非负防线**：`WHERE wq >= #{quantity}` / `WHERE oq >= #{quantity}` / `WHERE lq >= #{currentLockQuantity}` 防止取消/退款/付款确认/合并提交导致wq/oq/lq变负（MySQL默认不强制执行CHECK约束，需SQL层显式防护）
- **应用层软校验**：锁库存前预查询 `sq - lq` 值，快速失败并返回明确错误码
- **SQL层约束违反降级**：当UPDATE影响行数为0时，返回 `LOCK_QUANTITY_EXCEEDED` 错误码或触发告警

### Requirement: 库存模型支持 (Inventory Model)

系统 SHALL 支持多维度库存字段模型。

#### 字段定义

- **sq (Saleable Quantity)**: 可售库存 - 用户可见的可购买数量
- **wq (Withheld Quantity)**: 预扣库存 - 下单后从sq转移到wq
- **oq (Occupied Quantity)**: 占用库存 - 付款后从wq转移到oq
- **lq (Locked Quantity)**: 预锁库存 - 提前锁定到Redis的数量（合并提交后减去当前lockOrder的lockQuantity，多lockOrder并存时lq为所有ACTIVE lockOrder的lockQuantity之和）

#### 约束条件

```sql
-- SQL层硬约束（锁库存操作）
UPDATE inventory SET lq = lq + #{lockQuantity}
WHERE id = #{skuId} AND sq - lq >= #{lockQuantity}

-- SQL层硬约束（DB降级直接扣减操作）
-- WHERE条件必须扣除lq已锁定部分，防止DB直接扣减侵占lq锁定的库存导致合并提交后sq变负
UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity}
WHERE id = #{skuId} AND sq - lq >= #{quantity}

-- SQL层硬约束（合并提交操作）
-- lq减量更新：减去当前lockOrder的lockQuantity，而非重置为0
-- WHERE sq >= #{net_deduction} 作为最终防线，防止sq变负
-- WHERE lq >= #{currentLockQuantity} 防止lq变负（与sq/wq/oq非负约束一致）
UPDATE inventory SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}, lq = lq - #{currentLockQuantity}
WHERE id = #{skuId} AND sq >= #{net_deduction} AND lq >= #{currentLockQuantity}

-- SQL层硬约束（补偿合并操作）
-- 无需处理lq（lq已在原合并提交中减量更新）
-- WHERE sq >= #{net_deduction} 防止sq变负
UPDATE inventory SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}
WHERE id = #{skuId} AND sq >= #{net_deduction}

-- SQL层硬约束（MERGED取消操作）
-- WHERE wq >= #{quantity} 防止wq变负（MySQL默认不强制执行CHECK约束，需SQL层显式防护）
UPDATE inventory SET wq = wq - #{quantity}, sq = sq + #{quantity}
WHERE id = #{skuId} AND wq >= #{quantity}

-- SQL层硬约束（OCCUPIED退款操作）
-- WHERE oq >= #{quantity} 防止oq变负
UPDATE inventory SET oq = oq - #{quantity}, sq = sq + #{quantity}
WHERE id = #{skuId} AND oq >= #{quantity}

-- SQL层硬约束（付款确认操作）
-- WHERE wq >= #{quantity} 防止wq变负
UPDATE inventory SET wq = wq - #{quantity}, oq = oq + #{quantity}
WHERE id = #{skuId} AND wq >= #{quantity}

-- 字段非负约束
CHECK (sq >= 0 AND wq >= 0 AND oq >= 0 AND lq >= 0)

-- 合并提交后lq减去当前lockOrder的lockQuantity，多lockOrder并存时跨周期不会错误清零
-- 未卖出的库存自然保留在sq中（sq只减实际卖出量）
```

### Requirement: 可观测性 (Observability)

系统 SHALL 提供核心业务指标监控能力，基于 Micrometer + Prometheus 实现。

#### 核心监控指标

| 指标名                               | 类型                  | 说明                         |
| --------------------------------- | ----------------   | -------------------------- |
| store.deduct.redis.success.count  | Counter             | Redis分桶扣减成功次数              |
| store.deduct.redis.fallover.count | Counter             | 单桶耗竭fallover到其他桶的次数        |
| store.deduct.redis.degrade.count  | Counter             | 降级到DB直接扣减的次数               |
| store.deduct.redis.degrade.ratio  | Gauge               | 降级DB扣减比例（降级数/总扣减数）         |
| store.merge.delay.ms              | Timer               | 合并提交延迟（从明细创建到合并完成的耗时）      |
| store.merge.batch.size            | DistributionSummary | 每次合并处理的明细数量                |
| store.lock.utilization            | Gauge               | 锁库存利用率（实际Redis扣减量 / lq锁定量） |
| store.lock.expire.count           | Counter             | 锁库存超时自动释放次数                |
| store.redis.compensate.count      | Counter             | Redis回补次数（明细插入失败后回补）       |
| store.reconcile.mismatch.count    | Counter             | 对账任务检测到的lq与Redis各桶sum不一致次数 |
| store.auto-lock.create.count      | Counter             | 自动锁库存创建lockOrder次数            |
| store.auto-lock.fail.count        | Counter             | 自动锁库存创建失败次数（可用额度不足）     |
| store.active-lock.route.hit.count | Counter             | 活跃lockOrder路由缓存命中次数          |
| store.active-lock.route.miss.count| Counter             | 活跃lockOrder路由缓存未命中次数（需查DB）  |
| store.compensate.merge.count      | Counter             | 补偿合并执行次数                   |
| store.compensate.merge.fail.count | Counter             | 补偿合并失败次数（sq不足等）            |
| store.emergency.unlock.count      | Counter             | 紧急解锁执行次数                   |
| store.emergency.degrade.count     | Counter             | 紧急降级开关触发次数（扣减路径因emergency_degrade跳过Redis） |
| store.merge.crash.recover.count   | Counter             | 启动时崩溃恢复补偿清理Redis分桶次数       |
| store.auto-lock.event.drop.count  | Counter             | 自动锁库存异步事件丢弃次数（线程池满等）     |
| store.cancel.refund.wq.insufficient.count | Counter    | 取消/退款时wq不足告警次数            |
| store.cancel.refund.oq.insufficient.count | Counter    | 退款时oq不足告警次数              |

***

## MODIFIED Requirements

（暂无，此为新系统）

## REMOVED Requirements

（暂无）

***

## Architecture Overview

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      调用方（交易/订单）                        │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   Store Service (库存服务)                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  锁库存模块   │  │ 扣减控制器  │  │    合并提交调度器     │  │
│  │ (LockService)│  │(Controller)│  │ (MergeScheduler)    │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                     │             │
│         ▼                ▼                     ▼             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  DB锁库存    │  │ Redis分桶   │  │   明细聚合服务       │  │
│  │  操作        │  │ Lua扣减    │  │ (DetailAggregator)  │  │
│  │  +Redis初始化│  │ +DB明细    │  │ +分布式锁+事务       │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                     │             │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────────┴──────────┐  │
│  │  对账任务    │  │  降级DB扣减  │  │  锁超时释放任务      │  │
│  │ (Reconcile) │  │ (Fallback)  │  │ (LockExpireCleaner) │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│                                                              │
│  ┌──────────────────────┐  ┌──────────────────────────────┐  │
│  │ 自动锁库存模块        │  │ 活跃lockOrder路由            │  │
│  │ (AutoLockService)    │  │ (ActiveLockRouter)           │  │
│  └──────────────────────┘  └──────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────┐  ┌──────────────────────────────┐  │
│  │ 补偿合并模块          │  │ 紧急降级模块                 │  │
│  │ (CompensateService)  │  │ (EmergencyService)           │  │
│  └──────────────────────┘  └──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
          │                │                     │
          ▼                ▼                     ▼
┌─────────────────┐ ┌───────────────┐  ┌─────────────────────┐
│   MySQL DB      │ │   Redis       │  │   MySQL DB          │
│ ┌─────────────┐ │ │ ┌───────────┐ │  │ ┌─────────────────┐ │
│ │ inventory表  │ │ │ │ per-      │ │  │ ┌─────────────────────────────┐ │
│ │ (sq/wq/oq/lq)│ │ │ │ lockOrder │ │  │ │ lock_inventory_order表      │ │
│ └─────────────┘ │ │ │ buckets   │ │  │ │ (锁库存单据,ACTIVE/ARCHIVED) │ │
│                 │ │ │ (16个桶)   │ │  │ ├─────────────────────────────┤ │
│                 │ │ │ total_    │ │  │ │ deduction_detail表          │ │
│                 │ │ │ remaining │ │  │ │ (扣减明细,含状态机+           │ │
│                 │ │ │ 分桶索引   │ │  │ │  merge_batch_id+幂等索引)    │ │
│                 │ │ │ (per-     │ │  │ ├─────────────────────────────┤ │
│                 │ │ │ lockOrder │ │  │ │ refund_detail表             │ │
│                 │ │ │ 元数据)   │ │  │ │ (回补明细,关联原扣减明细)     │ │
│                 │ └───────────┘ │  │ └─────────────────────────────┘ │
│                 └───────────────┘  └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 基础概念

#### 库存扣减明细（也叫单据）

库存扣减明细是库存扣减的**快照**，是本系统最核心的概念之一，明细是不可或缺的。明细的作用：

1. **记录扣减信息**：下单时交易告知扣多少库存，后续付款或订单取消时，上游不用关心库存的回补数量，库存内部从明细恢复
2. **幂等性保障**：同一个单据调了两次扣减；单据回补超时，重试可幂等；同一订单同一SKU重复扣减通过唯一索引去重
3. **生命周期管理**：负责库存扣减生命周期状态流转

本系统存在四种明细类型，拆分为三张独立表存储：

| 明细类型 | 存储表 | 定位 | 创建时机 | 生命周期终态 |
|----------|--------|------|----------|-------------|
| **锁库存单据** | lock\_inventory\_order | 父单据 | 锁库存操作时 | 所有子单据终态+无待处理回收后结束（ACTIVE→ARCHIVED） |
| **合并下单明细** | deduction\_detail | 子单据 | Redis分桶预扣减成功时 | CANCELLED / REFUNDED |
| **普通下单明细** | deduction\_detail | 独立单据 | DB降级直接扣减时 | CANCELLED / REFUNDED |
| **回补明细** | refund\_detail | 关联单据 | 取消/退款时 | 创建即生效（MERGED） |

> 以下核心数据流中标注了各明细类型的创建时机和流转路径，与"明细分类体系"表格对应。

### 核心数据流

```
0. [自动锁库存阶段] AutoLockService.autoLock(skuId)
   → 热点识别：感知交易系统的热点品库存查询
   → 查询当前活跃lockOrder：inventory:active_lock:{skuId}
   → IF 无活跃lockOrder 或 活跃lockOrder的total_remaining低于阈值:
       → 创建新lockOrder（同下方预热阶段流程，严格按时序执行）
       → 在Redis分桶初始化完成、DB lq更新完成、lockOrder记录插入完成后，原子更新路由缓存
   → IF 活跃lockOrder数量已达上限（store.auto-lock.max-active）:
       → 跳过创建，等待现有lockOrder合并提交后再创建
   → 连锁触发：扣减请求中同步快检total_remaining + 后台定时任务兜底

1. [预热阶段] LockService.lockInventory(skuId, lockQuantity, idempotentKey)
   → 幂等检查：SELECT id, status FROM lock_inventory_order WHERE idempotent_key = #{idempotentKey}
     → IF 已存在且status=ACTIVE: 直接返回已有lockOrderId，不重复执行
     → IF 已存在且status=ARCHIVED: 返回错误码 LOCK_ORDER_ALREADY_ARCHIVED
   → 预生成lockOrderId（雪花算法），用于构造Redis Key
   → Step 1 - Redis: 使用Lua脚本原子初始化N个per-lockOrder分桶（inventory:lock:{lockOrderId}:bucket:0..N-1）
            每个分桶设置 count = actualLockQuantity / N
            分桶索引元数据（inventory:lock:{lockOrderId}:meta）
            总余量Key（inventory:lock:{lockOrderId}:total_remaining = actualLockQuantity）
            > Lua脚本保证所有分桶+meta+total_remaining要么全部初始化成功，要么全部不初始化
   → Step 2 - DB事务内:
     a. UPDATE inventory SET lq = lq + #{actualLockQuantity}
        WHERE id = #{skuId} AND sq - lq >= #{actualLockQuantity}
        → actualLockQuantity = min(lockQuantity, (sq - lq) * (1 - reserve-ratio))（reserve-ratio始终生效，包括部分锁定场景）
        → 当计算结果 < min-lock-quantity 时返回错误码 LOCK_QUANTITY_EXCEEDED
     b. INSERT lock_inventory_order（status=ACTIVE，lock_quantity=#{actualLockQuantity},
        idempotent_key=#{idempotentKey}, merge_completed=false）→ 父单据
     → IF DB事务失败: 回滚DB + Lua脚本原子清理Redis分桶
   → Step 3 - 路由更新: 使用Lua脚本原子执行 SET inventory:active_lock:{skuId} = newLockOrderId + RPUSH inventory:active_lock_history:{skuId} = newLockOrderId
     → 必须在Step 1和Step 2全部完成后执行
   → 创建锁库存单据（含过期时间），返回lockOrderId

2. [下单阶段] Controller.deduct(orderId, skuId, quantity[, lockOrderId])
   → 【幂等检查】SELECT 1 FROM deduction_detail WHERE order_id = #{orderId} AND sku_id = #{skuId}
     → IF 已存在: 直接返回成功（幂等），无需INCR回补（Lua扣减尚未执行）
   → 【路由解析】IF 未指定lockOrderId:
       查询 inventory:active_lock:{skuId} → 获取活跃lockOrderId
       IF 路由缓存不存在:
         查询DB: SELECT id FROM lock_inventory_order WHERE sku_id=#{skuId} AND status='ACTIVE' LIMIT 1
         IF 找到: 重建路由缓存
         IF 未找到: 降级走DB直接扣减路径（同下方路径B）
   → 【紧急降级开关检查】查询 inventory:emergency_degrade:{skuId}
     → IF 存在且值为true: 跳过Redis路径，直接走DB降级扣减（同下方路径B）
     → 紧急降级期间Redis分桶正在被批量清零，若此时走Redis路径可能扣减到即将被清除的分桶，导致数据不一致
   → 【扣减屏障检查】查询该lockOrder的分桶索引缓存（inventory:lock:{lockOrderId}:meta）是否存在且有效
     → IF 分桶索引不存在或已标记失效（该lockOrder正在合并提交或已完成合并）:
         → 查询历史路由 inventory:active_lock_history:{skuId}，按创建时间倒序遍历
           （最多max-history-scan个，总耗时不超过history-scan-timeout-ms，余量为0的跳过）
         → IF 找到有效的旧lockOrder: 使用旧lockOrder继续扣减
         → IF 仍无效: 降级走DB直接扣减路径（同下方路径B），插入普通下单明细
   → 从分桶索引缓存获取该lockOrder的桶列表
   → 随机选择一个桶，执行Lua脚本原子扣减

   【路径A：合并下单明细】Redis分桶预扣减路径
   → IF Lua返回1（成功）或返回2（成功且分桶耗尽）:
       DB: INSERT deduction_detail（deduct_path=MERGE_BUCKETS,
           status='PENDING', lock_order_id=当前锁库存单据ID,
           bucket_index=实际扣减的桶编号, order_id, sku_id）→ 子单据
       IF Lua返回2: 异步触发该lockOrder的合并提交
       RETURN success
   → IF Lua返回0（当前桶不足）:
       fallover到其他桶重试（最多M次）
   → IF DB明细插入失败（唯一索引冲突除外）:
       Redis: INCR回补bucket_index对应的分桶计数和total_remaining

   【路径B：普通下单明细】DB直接扣减路径（降级）
   → IF 全部桶不足或Redis超时/异常:
       DB事务内原子执行:
         UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity}
         WHERE id = #{skuId} AND sq - lq >= #{quantity}
         INSERT deduction_detail（deduct_path=DIRECT_DB,
               status='MERGED', lock_order_id=NULL, order_id, sku_id）
       IF DB扣减失败（sq-lq可用额度不足）: 返回 INSUFFICIENT_STOCK

3. [合并阶段] MergeScheduler.triggerMerge() [延迟可配置ms]
   → 获取分布式锁（merge:{lockOrderId}）
   → 失效该lockOrder的per-lockOrder分桶索引缓存（扣减屏障，性能优化）
   → 分配merge_batch_id（前缀MERGE-{uuid}）
   → @Transactional事务内（先标记后计算）:
       UPDATE deduction_detail SET status='MERGED', merge_batch_id = #{batchId}
           WHERE lock_order_id = #{lockOrderId} AND status='PENDING' AND merge_batch_id IS NULL
           → 获取行锁，阻止并发CANCEL；原子标记所有PENDING为MERGED
       IF 影响行数为0: 释放分布式锁，返回（幂等：二次合并无待合并明细）
       SELECT COALESCE(SUM(quantity), 0) AS net_deduction FROM deduction_detail WHERE merge_batch_id = #{batchId}
           → 从实际标记的明细计算净扣减值，与MERGED明细完全一致
       SELECT lock_quantity AS currentLockQuantity FROM lock_inventory_order WHERE id = #{lockOrderId}
           → 获取当前lockOrder的锁定量，用于lq减量更新
       UPDATE inventory SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}, lq = lq - #{currentLockQuantity}
           WHERE id = #{skuId} AND sq >= #{net_deduction} AND lq >= #{currentLockQuantity}
           → lq减量更新，支持多lockOrder并存
           → WHERE sq >= #{net_deduction} 最终防线
           → WHERE lq >= #{currentLockQuantity} 防止lq变负
       UPDATE lock_inventory_order SET status='ARCHIVED' WHERE id = #{lockOrderId}
   → 释放分布式锁（提前释放：后续Step 5-6是幂等操作，重复DEL/UPDATE不影响正确性）
   → 清零/删除该lockOrder的Redis分桶（bucket keys、meta key、total_remaining key）
   → UPDATE lock_inventory_order SET merge_completed = true WHERE id = #{lockOrderId}

4. [回收阶段] LockExpireCleaner.checkExpired() [定时扫描]
   → 扫描超过过期时间的锁库存单据（lock_inventory_order WHERE status='ACTIVE' AND expire_time < NOW()）
   → 触发合并提交流程释放库存（lq减去lockQuantity，status→ARCHIVED）

5. [回补阶段] 取消/退款时
   → IF 原明细status=PENDING（合并提交前取消）:
       UPDATE deduction_detail SET status='CANCELLED'
       → 使用Lua脚本原子执行条件INCR回补（检查meta有效性 + INCR在同一脚本内）:
         IF meta有效: INCR回补bucket_index对应的分桶计数和total_remaining（防止少卖）
         IF meta已失效: 跳过INCR回补（桶即将或已被清除）
   → IF 原明细status=MERGED（合并提交后取消）:
       INSERT refund_detail（关联原扣减明细ID）
       UPDATE deduction_detail SET status='CANCELLED'
       UPDATE inventory SET wq = wq - #{quantity}, sq = sq + #{quantity} WHERE id = #{skuId} AND wq >= #{quantity}
       → IF UPDATE影响行数为0: 触发告警，进入人工处理流程
   → IF 原明细status=OCCUPIED（付款后退款）:
       INSERT refund_detail（关联原扣减明细ID）
       UPDATE deduction_detail SET status='REFUNDED'
       UPDATE inventory SET oq = oq - #{quantity}, sq = sq + #{quantity} WHERE id = #{skuId} AND oq >= #{quantity}
       → IF UPDATE影响行数为0: 触发告警，进入人工处理流程

6. [补偿阶段] CompensateService.compensateOrphanDetails() [定时扫描]
   → 扫描孤立PENDING明细: SELECT d.* FROM deduction_detail d INNER JOIN lock_inventory_order l ON d.lock_order_id = l.id WHERE d.status = 'PENDING' AND l.status = 'ARCHIVED'
   → 按lockOrderId维度获取分布式锁（compensate:{lockOrderId}）
   → 事务内"先标记后计算":
       UPDATE deduction_detail SET status='MERGED', merge_batch_id = #{compensateBatchId}
         WHERE lock_order_id = #{lockOrderId} AND status='PENDING' AND merge_batch_id IS NULL
       SELECT COALESCE(SUM(quantity), 0) AS net_deduction FROM deduction_detail WHERE merge_batch_id = #{compensateBatchId}
       UPDATE inventory SET sq = sq - #{net_deduction}, wq = wq + #{net_deduction}
         WHERE id = #{skuId} AND sq >= #{net_deduction}
   → 释放分布式锁

7. [崩溃恢复阶段] 应用启动时
   → 扫描未完成的合并提交: SELECT * FROM lock_inventory_order
     WHERE status='ARCHIVED' AND merge_completed = false
   → 对每条记录补偿清理对应的Redis分桶（bucket keys、meta key、total_remaining key）
     → 优化：先EXISTS检查Key是否存在，不存在则跳过DEL（避免无效操作），直接更新merge_completed
   → UPDATE lock_inventory_order SET merge_completed = true WHERE id = #{lockOrderId}

8. [紧急降级阶段] EmergencyService.emergencyUnlock(skuId) [管理接口]
   → 当Redis不可用且sq-lq=0时，人工触发紧急解锁
   → 对所有ACTIVE lockOrder逐个触发紧急合并提交（按lockOrderId维度加分布式锁串行处理）
   → 确保Redis分桶和lq同步释放，使DB降级路径可用
```

### 技术栈选型（已确认）

- **框架**: Spring Boot 4.0.6 + **MyBatis-Plus**（ORM持久层框架）
  > 注: Spring Boot 4.x 为项目已采用版本，与 Spring Boot 3.x API 兼容
- **缓存**: Redis + **Redisson客户端**（支持分布式锁、原子操作、高性能序列化）
- **数据库**: MySQL 8.x（InnoDB引擎，支持事务）
- **调度**: **Spring @Scheduled**（定时合并任务）
- **监控**: Micrometer + Prometheus（扣减TPS、Redis命中率、合并延迟）

#### 技术栈详细说明

**MyBatis-Plus 特性应用**

- 使用 `@TableName`、`@TableId`、`@TableField` 注解简化实体映射
- 利用 `IService`、`BaseMapper` 提供的通用CRUD能力
- 通过 `LambdaQueryWrapper` 构建类型安全的查询条件
- 使用 `@InterceptorIgnore` 处理特殊SQL场景（如自定义更新逻辑）

**Redisson 核心组件**

- `RAtomicLong`：实现分桶库存的原子DECR/INCR操作
- `RBucket`：存储分桶索引元数据
- `RLock`：分布式锁保障合并操作和补偿操作的互斥性
- `RScript`：执行Lua脚本保证"检查+扣减"复合操作原子性

**Spring @Scheduled 调度策略**

```java
@Scheduled(fixedDelayString = "${store.merge.delay-ms:1000}", initialDelay = 1000)
public void mergePendingDeductions() {
    // 合并待处理的扣减明细
}
```

- 支持动态调整调度间隔（通过配置参数 `store.merge.delay-ms`）
- 结合 `@Async` 实现异步非阻塞执行
- 集成 Spring Boot Actuator 暴露调度状态监控端点

#### 分桶数量指导原则

- **N值选择依据**：每桶支撑约1000 TPS，总目标TPS = N × 1000（如目标10000 TPS → N=10\~12）
- **N越大**：热点分散效果越好，但Redis初始化和合并复杂度越高
- **N越小**：管理简单，但单桶成为热点瓶颈的风险增加
- **默认值**：N=16（通过配置 `store.bucket.count` 可调整）
- **动态调整**：当前版本不支持运行时动态调整N，需在锁库存时确定

### 配置参数汇总

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| store.bucket.count | 16 | 分桶数量 |
| store.merge.delay-ms | 1000 | 合并提交延迟（毫秒） |
| store.merge.idle-qps-threshold | 100 | 活跃度衰减触发阈值（QPS） |
| store.auto-lock.quantity | 10000 | 每次自动锁库存的锁定量 |
| store.auto-lock.trigger-ratio | 0.5 | 自动锁库存触发阈值（余量比例） |
| store.auto-lock.max-active | 2 | 同一SKU最大活跃lockOrder数 |
| store.auto-lock.min-lock-quantity | 100 | 最小有效锁定量 |
| store.auto-lock.reserve-ratio | 0.1 | 预留DB降级额度比例 |
| store.auto-lock.check-interval-ms | 500 | 自动锁库存检测间隔（毫秒） |
| store.routing.max-history-scan | 3 | 历史路由最大遍历数量 |
| store.routing.history-scan-timeout-ms | 5 | 历史路由遍历超时（毫秒） |
| store.redis.fail-threshold | 5 | Redis连续超时触发紧急降级次数 |

### 性能指标预期

- **扣减TPS**: 相比纯DB提升5-10倍（取决于分桶数和Redis性能）
- **一致性延迟**: ≤ 2秒（合并窗口期）
- **可用性**: Redis故障时自动降级至DB模式；当lq < sq时保证核心链路不中断；当lq = sq且Redis不可用时，需通过紧急解锁接口释放lq后恢复可用性
