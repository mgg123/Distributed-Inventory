# 基于Redis分布式强一致库存扣减系统 — 开发实施计划

## 计划概述

根据 `tasks.md` 定义的11个Phase，按依赖关系逐步实施。每个Phase完成后进行设计文档合规性检查，再向用户确认后进入下一个Phase。

**编码规范**：严格遵循《阿里巴巴Java开发手册》
**架构风格**：DDD + COLA 四层架构（Adapter → App → Domain ← Infrastructure）
**设计文档**：以 `design/` 目录下的5份设计文档为验收标准

---

## 实施步骤

### Phase 1: 基础设施搭建与项目骨架（Task 1）

**目标**：创建完整的DDD+COLA四层包结构，配置所有依赖和基础设施。

**实施内容**：
1. 修改 `pom.xml` 添加依赖：Redisson、MyBatis-Plus、MySQL Connector、HikariCP、Micrometer、Prometheus、Swagger
2. 创建 `application.properties` / `application.yml` 配置：Redisson Cluster连接、MySQL数据源、Store业务参数
3. 创建DDD+COLA四层包结构：
   - `com.mgg.exp.store.adapter` (controller/command/query)
   - `com.mgg.exp.store.app` (service/impl/assembler/task)
   - `com.mgg.exp.store.domain` (inventory/deduction/refund/routing 各含aggregate/entity/valueobject/service/repository/gateway/event)
   - `com.mgg.exp.store.infrastructure` (repository/gateway/converter/dataobject/mapper/lua/config)
   - `com.mgg.exp.store.common` (enums/exception/result/util)
4. 创建 `StoreApplication.java` 启动类
5. 创建 `StoreProperties.java` 配置属性类（store.bucket.count, store.merge.delay-ms, store.auto-lock.* 等）
6. 创建 `RedisConfig.java`、`RedissonConfig.java`、`MybatisPlusConfig.java`、`ThreadPoolConfig.java`

**验收标准**：
- [ ] 包结构严格匹配 `project-directory-structure-design.md`
- [ ] 应用可正常启动，无Bean注入错误
- [ ] Redisson连接Redis Cluster成功
- [ ] MyBatis-Plus连接MySQL成功
- [ ] 所有配置项与 `system-detailed-design.md` 的StoreProperties定义一致

---

### Phase 2: 数据模型与DDL（Task 2）

**目标**：创建4张表的DDL和对应的PO/Mapper/Converter。

**实施内容**：
1. 创建SQL DDL脚本（4张表）：
   - `inventory`（含sq/wq/oq/lq + CHECK约束 chk_non_negative）
   - `lock_inventory_order`（含uk_idempotent_key、idx_sku_status、idx_expire_status、bucket_info JSON）
   - `deduction_detail`（含uk_order_sku、idx_lock_order_status、idx_merge_batch、5状态枚举）
   - `refund_detail`（含uk_ref_detail_request、refund_request_id字段）
2. 创建4个PO类：`InventoryPO`、`LockInventoryOrderPO`、`DeductionDetailPO`、`RefundDetailPO`（@TableName注解）
3. 创建4个Mapper接口：继承 `BaseMapper<T>`，添加自定义方法
4. 实现自定义Mapper XML：
   - `InventoryMapper`：锁库存UPDATE（WHERE sq-lq>=）、合并提交UPDATE（WHERE sq>= AND lq>=）、DB降级UPDATE、付款确认UPDATE、取消/退款UPDATE
   - `LockOrderMapper`：selectByIdempotentKey（含status）、selectExpiredActive、updateStatusToArchived、updateMergeCompleted
   - `DeductionDetailMapper`：markPendingAsMerged（WHERE status=PENDING AND merge_batch_id IS NULL）、calculateNetDeduction（COALESCE(SUM,0)）
   - `RefundDetailMapper`：按ref_detail_id+refund_request_id查询
5. 创建4个Converter：PO ↔ Domain对象转换

**验收标准**：
- [ ] DDL严格匹配 `database-design.md` 的4张表定义（字段名、类型、约束、索引）
- [ ] 所有核心SQL操作与 `database-design.md` 的"核心SQL操作"一致
- [ ] 合并提交SQL包含 `WHERE sq >= #{netDeduction} AND lq >= #{currentLockQuantity}`
- [ ] calculateNetDeduction 使用 `COALESCE(SUM(quantity), 0)`
- [ ] refund_detail 包含 refund_request_id 字段和 uk_ref_detail_request 唯一索引
- [ ] PO类使用包装类型（Integer而非int），符合阿里巴巴规范

---

### Phase 3: Domain领域层核心模型（Task 3）

**目标**：实现DDD领域层，包含所有值对象、聚合根、实体、领域服务、仓储接口、网关接口、领域事件。**Domain层不依赖任何外部框架**。

**实施内容**：
1. 值对象（domain/*/valueobject）：
   - `Quantity`（不可变，非负校验，add/subtract/isLessThan/isNegative行为）
   - `SkuId`、`OrderId`、`LockOrderId`、`DetailId`、`MergeBatchId`
   - `DeductionStatus`（5状态枚举+状态机转换规则）、`LockOrderStatus`、`DeductPath`
   - `BucketMeta`、`BucketIndex`、`LockResult`、`DeductResult`、`MergeResult`
   - `RefundId`、`RefundRequestId`、`RefundQuantity`
   - `ActiveLockRoute`、`RouteResolveResult`
2. 聚合根（domain/*/aggregate）：
   - `InventoryAggregate`（sq/wq/oq/lq + lock/mergeCommit/release行为）
   - `DeductionDetailAggregate`（状态机 + cancel/markMerged行为）
3. 实体（domain/*/entity）：
   - `LockInventoryOrder`、`DeductionDetail`、`RefundDetail`
4. 领域服务（domain/*/service）：
   - `InventoryDomainService`、`DeductionDomainService`、`RefundDomainService`、`RoutingDomainService`
5. 仓储接口（domain/*/repository）：
   - `InventoryRepository`、`LockOrderRepository`、`DeductionDetailRepository`、`RefundDetailRepository`
6. 网关接口（domain/gateway）：
   - `RedisBucketGateway`、`ActiveLockRouterGateway`、`DistributedLockGateway`、`EmergencyDegradeGateway`
7. 领域事件（domain/*/event）：
   - `AutoLockEvent`、`InventoryDeductedEvent`、`MergeCommittedEvent`

**验收标准**：
- [ ] Domain层无Spring/MyBatis/Redisson等框架import（@Component/@Service轻量注解除外）
- [ ] 值对象不可变（final字段 + 无setter）
- [ ] Quantity含非负校验和算术行为
- [ ] 聚合根包含业务行为方法（非贫血模型）
- [ ] 仓储接口和网关接口仅定义方法签名，无实现
- [ ] 类命名符合阿里巴巴规范（接口无I前缀，异常以Exception结尾，枚举以Enum结尾）

---

### Phase 4: Infrastructure基础设施层实现（Task 4）

**目标**：实现Domain层定义的所有接口，包含4个Lua脚本、4个网关实现、4个仓储实现、事件发布实现。

**实施内容**：
1. Lua脚本（resources/lua/）：
   - `deduct.lua`：含total_remaining检查 + 返回值2（分桶耗尽）
   - `init_buckets.lua`：原子初始化分桶+meta+total_remaining
   - `cleanup_buckets.lua`：原子清理分桶
   - `incr_refund.lua`：原子条件INCR回补（含meta有效性检查）
2. 网关实现（infrastructure/gateway）：
   - `RedisBucketGatewayImpl`：Lua脚本执行器（RScript）、分桶初始化/清理/扣减/回补，Key使用Hash Tag格式
   - `ActiveLockRouterGatewayImpl`：路由缓存CRUD，Lua脚本原子更新路由+历史
   - `DistributedLockGatewayImpl`：Redisson RLock封装
   - `EmergencyDegradeGatewayImpl`：紧急降级开关（SETNX/GET/DEL）
3. 仓储实现（infrastructure/repository）：
   - `InventoryRepositoryImpl`：库存CRUD + 自定义UPDATE（含lq非负约束）
   - `LockOrderRepositoryImpl`：锁库存单据CRUD + selectByIdempotentKey(含status) + selectExpiredActive
   - `DeductionDetailRepositoryImpl`：明细CRUD + markPendingAsMerged + calculateNetDeduction(COALESCE)
   - `RefundDetailRepositoryImpl`：回补明细CRUD
4. 事件实现：`DomainEventPublisherImpl`
5. 配置类：`RedisConfig`、`RedissonConfig`、`MybatisPlusConfig`、`ThreadPoolConfig`、`StoreProperties`

**验收标准**：
- [ ] 4个Lua脚本与 `system-detailed-design.md` 的脚本定义完全一致
- [ ] deduct.lua返回值：0=不足，1=成功，2=成功且分桶耗尽
- [ ] incr_refund.lua先检查meta有效性再INCR
- [ ] Redis Key使用Hash Tag格式（`inventory:{lockOrderId}:lock:bucket:{n}`）
- [ ] 合并提交SQL包含 `AND lq >= #{currentLockQuantity}`
- [ ] markPendingAsMerged WHERE条件包含 `AND merge_batch_id IS NULL`
- [ ] calculateNetDeduction使用COALESCE
- [ ] 实现类命名以Impl结尾，符合阿里巴巴规范

---

### Phase 5: App应用层服务编排（Task 5）

**目标**：实现6个应用服务，编排领域服务和基础设施，管理事务。

**实施内容**：
1. `InventoryLockAppService` / Impl：
   - Step 0：幂等检查（SELECT id, status，ARCHIVED返回LOCK_ORDER_ALREADY_ARCHIVED）
   - Step 1：Redis Lua初始化分桶
   - Step 2：DB事务（UPDATE lq + INSERT lockOrder），失败时Lua清理Redis
   - Step 3：路由更新（重试3次+后台补偿+启动时修复）
   - 部分锁定：actualLockQuantity = min(lockQuantity, (sq-lq)*(1-reserveRatio))
2. `InventoryDeductAppService` / Impl：
   - 紧急降级开关检查（emergency_degrade）
   - 路由解析 → Redis扣减（Lua返回1/2/0）→ DB明细 → 降级DB扣减
   - 返回值2时异步触发合并提交
   - 扣减幂等（uk_order_sku + INCR回补Redis）
3. `InventoryMergeAppService` / Impl：
   - 分布式锁 → 扣减屏障 → 先标记后计算 → lq减量更新+非负约束
   - Step 4a影响0行跳过（幂等保障）
   - COALESCE(SUM, 0)防止NULL
   - 提前释放分布式锁 → Redis清理 → merge_completed
4. `InventoryRefundAppService` / Impl：
   - PENDING取消：条件INCR回补Lua
   - MERGED取消：wq回补sq + refund_detail
   - OCCUPIED退款：oq回补sq + refund_detail（含refund_request_id业务幂等）
   - 付款确认：MERGED→OCCUPIED，wq→oq
5. `AutoLockAppService` / Impl：
   - 事件去重（SETNX auto_lock_pending TTL=5s）
   - 分布式锁 + max-active限制
6. `EmergencyAppService` / Impl：
   - 逐个合并提交 / SET lq=0 + 同步ARCHIVED lockOrder + emergency_degrade开关

**验收标准**：
- [ ] 锁库存严格时序与 `system-detailed-design.md` 的Step 0→1→2→3一致
- [ ] 扣减流程包含紧急降级开关检查
- [ ] 合并提交流程包含Step 4a影响0行跳过逻辑
- [ ] 合并提交SQL包含lq非负约束
- [ ] 分布式锁在事务完成后提前释放
- [ ] @Transactional仅在AppService层使用
- [ ] PENDING取消使用Lua原子条件INCR回补（含meta检查）
- [ ] refund_detail插入包含refund_request_id字段

---

### Phase 6: 定时任务与补偿机制（Task 6）

**目标**：实现4个定时任务，覆盖合并调度、补偿、超时释放、自动锁库存检测。

**实施内容**：
1. `MergeSchedulerTask`：合并提交调度（延迟触发/分桶耗尽触发/活跃度衰减触发）
2. `CompensateTask`：孤立PENDING明细补偿 + 崩溃恢复 + 路由缓存补偿修复
3. `LockExpireTask`：锁超时释放
4. `AutoLockCheckTask`：自动锁库存检测（路由缓存优先+DB定期补充+事件去重）

**验收标准**：
- [ ] 合并调度3种触发条件全部实现
- [ ] 崩溃恢复扫描merge_completed=false的ARCHIVED记录
- [ ] 路由缓存补偿扫描5秒前ACTIVE但无路由的lockOrder
- [ ] 活跃度衰减使用滑动窗口QPS计数器

---

### Phase 7: Adapter适配层（Task 7）

**目标**：实现REST API和CQRS分离。

**实施内容**：
1. `InventoryWriteController` + `InventoryReadController`
2. 5个CmdExe + 3个QueryExe
3. `InventoryAssembler`
4. `GlobalExceptionHandler`
5. Swagger/OpenAPI配置

**验收标准**：
- [ ] API路径与 `interface-design.md` 一致
- [ ] 锁库存接口包含reserveRatio参数
- [ ] 退款接口包含refundRequestId参数
- [ ] 错误码与 `interface-design.md` 一致（含LOCK_ORDER_ALREADY_ARCHIVED）
- [ ] Controller不包含业务逻辑

---

### Phase 8: Common公共模块（Task 8）

**目标**：实现枚举、异常、统一返回、工具类。

**实施内容**：
1. 枚举：DeductionStatusEnum、DeductPathEnum、LockOrderStatusEnum、ErrorCodeEnum
2. 异常：6个自定义异常类
3. Result<T>
4. IdGenerator（雪花算法）

**验收标准**：
- [ ] 枚举以Enum结尾，异常以Exception结尾
- [ ] ErrorCodeEnum包含interface-design.md定义的所有错误码

---

### Phase 9: 可观测性（Task 9）

**目标**：实现核心业务指标监控。

**实施内容**：
1. MetricsCollector：7类指标（扣减/合并/锁库存/补偿/自动锁库存/路由/紧急降级）
2. Prometheus端点配置
3. Grafana仪表盘JSON

**验收标准**：
- [ ] 指标名称与 `system-detailed-design.md` 一致
- [ ] 包含emergency.degrade.count和merge.crash.recover.count

---

### Phase 10: 集成测试（Task 10）

**目标**：编写8类集成测试，覆盖所有核心场景。

**实施内容**：
1. 锁库存流程测试
2. 扣减流程测试
3. 合并提交流程测试
4. 回补流程测试
5. 并发场景测试
6. 补偿机制测试
7. 紧急降级测试
8. Redis Cluster兼容性测试

**验收标准**：
- [ ] 所有测试通过
- [ ] 并发扣减不超卖不少卖
- [ ] Step 4a影响0行跳过验证
- [ ] emergency_degrade开关验证

---

### Phase 11: 性能测试与优化（Task 11）

**目标**：压测验证TPS和一致性。

**实施内容**：
1. JMeter/Gatling压测脚本
2. 纯DB vs Redis TPS对比
3. 参数调优
4. 极端场景一致性验证

**验收标准**：
- [ ] 单SKU扣减TPS ≥ 10000
- [ ] 极端场景不超卖不少卖

---

## 执行策略

1. **逐Phase执行**：按Phase 1→2→3→4→5→6→7→8→9→10→11顺序
2. **每Phase完成后检查**：对照设计文档验证实现正确性
3. **用户确认后继续**：每个Phase完成后暂停，向用户展示验收结果，获得确认后再进入下一个Phase
4. **可并行优化**：Phase 8（Common）可与Phase 3并行；Phase 6/7可在Phase 5完成后并行
5. **编码规范检查**：每个Phase完成后检查是否符合阿里巴巴Java开发手册
