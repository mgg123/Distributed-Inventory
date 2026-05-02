# Tasks - 基于Redis分布式强一致库存扣减系统

> 版本 V2.0 — 同步spec.md第六轮评审修复（2026-05-02）
> 架构风格：DDD + COLA 分层架构

## Phase 1: 基础设施搭建与项目骨架
- [ ] Task 1: 项目依赖配置与DDD+COLA基础架构搭建
  - [ ] 1.1 添加Redisson依赖到pom.xml（redisson-spring-boot-starter）
  - [ ] 1.2 添加MyBatis-Plus依赖（mybatis-plus-spring-boot3-starter）
  - [ ] 1.3 添加MySQL Connector和数据库连接池配置（HikariCP）
  - [ ] 1.4 配置Redisson连接参数（Cluster模式，Hash Tag兼容）和MySQL数据源信息
  - [ ] 1.5 创建DDD+COLA四层包结构（adapter/app/domain/infrastructure/common）
  - [ ] 1.6 创建domain层限界上下文包（inventory/deduction/refund/routing）
  - [ ] 1.7 创建infrastructure层子包（repository/gateway/converter/dataobject/mapper/lua/config）
  - [ ] 1.8 创建adapter层CQRS子包（controller/command/query）
  - [ ] 1.9 配置Micrometer + Prometheus监控依赖和Actuator端点
  - [ ] 1.10 创建StoreApplication启动类和application.properties基础配置

## Phase 2: 数据模型与DDL
- [ ] Task 2: 设计并实现库存核心数据模型（基于MyBatis-Plus + DDD领域模型）
  - [ ] 2.1 创建inventory表DDL（含sq/wq/oq/lq字段、CHECK约束、四字段非负）
  - [ ] 2.2 创建lock_inventory_order表DDL（含idempotent_key唯一索引、idx_sku_status、idx_expire_status索引）
  - [ ] 2.3 创建deduction_detail表DDL（含uk_order_sku唯一索引、merge_batch_id索引、5状态枚举）
  - [ ] 2.4 创建refund_detail表DDL（含uk_ref_detail_request业务级幂等索引、refund_request_id字段）
  - [ ] 2.5 创建Infrastructure层PO数据对象（InventoryPO/LockInventoryOrderPO/DeductionDetailPO/RefundDetailPO）
  - [ ] 2.6 创建MyBatis-Plus Mapper接口（InventoryMapper/LockOrderMapper/DeductionDetailMapper/RefundDetailMapper）
  - [ ] 2.7 实现自定义Mapper方法（含sq-lq>=lockQuantity的UPDATE、lq>=currentLockQuantity的合并提交UPDATE、COALESCE(SUM)计算）
  - [ ] 2.8 创建Infrastructure层Converter（PO ↔ Domain对象转换器）

## Phase 3: Domain领域层核心模型
- [ ] Task 3: 实现DDD领域层核心模型（无外部依赖）
  - [ ] 3.1 创建值对象：Quantity（不可变，含非负校验、add/subtract/isLessThan行为）
  - [ ] 3.2 创建值对象：SkuId、OrderId、LockOrderId、DetailId、MergeBatchId
  - [ ] 3.3 创建值对象：DeductionStatus（5状态枚举+状态机转换规则）、LockOrderStatus、DeductPath
  - [ ] 3.4 创建值对象：BucketMeta（分桶元数据）、BucketIndex（桶编号）、LockResult、DeductResult、MergeResult
  - [ ] 3.5 创建值对象：RefundId、RefundRequestId（业务级幂等键）、RefundQuantity
  - [ ] 3.6 创建值对象：ActiveLockRoute（路由信息）、RouteResolveResult（路由解析结果）
  - [ ] 3.7 创建聚合根：InventoryAggregate（sq/wq/oq/lq + lock/mergeCommit/release行为）
  - [ ] 3.8 创建聚合根：DeductionDetailAggregate（含状态机、cancel/markMerged行为）
  - [ ] 3.9 创建实体：LockInventoryOrder、DeductionDetail、RefundDetail
  - [ ] 3.10 创建领域服务：InventoryDomainService（锁库存/释放领域逻辑）
  - [ ] 3.11 创建领域服务：DeductionDomainService（扣减/降级/回补领域逻辑）
  - [ ] 3.12 创建领域服务：RefundDomainService（取消/退款领域逻辑）
  - [ ] 3.13 创建领域服务：RoutingDomainService（路由解析/兜底领域逻辑）
  - [ ] 3.14 创建仓储接口：InventoryRepository、LockOrderRepository、DeductionDetailRepository、RefundDetailRepository
  - [ ] 3.15 创建网关接口：RedisBucketGateway、ActiveLockRouterGateway、DistributedLockGateway、EmergencyDegradeGateway
  - [ ] 3.16 创建领域事件：AutoLockEvent、InventoryDeductedEvent、MergeCommittedEvent

## Phase 4: Infrastructure基础设施层实现
- [ ] Task 4: 实现Infrastructure层（技术实现，实现Domain接口）
  - [ ] 4.1 实现RedisBucketGatewayImpl：Lua脚本执行器（RScript）、分桶初始化/清理/扣减/回补
  - [ ] 4.2 编写Lua脚本：deduct.lua（含total_remaining检查+返回值2分桶耗尽）
  - [ ] 4.3 编写Lua脚本：init_buckets.lua（原子初始化分桶+meta+total_remaining）
  - [ ] 4.4 编写Lua脚本：cleanup_buckets.lua（原子清理分桶）
  - [ ] 4.5 编写Lua脚本：incr_refund.lua（原子条件INCR回补，含meta有效性检查）
  - [ ] 4.6 实现ActiveLockRouterGatewayImpl：路由缓存CRUD（SET/GET/LPUSH/LRANGE/LREM），使用Hash Tag Key格式
  - [ ] 4.7 实现DistributedLockGatewayImpl：Redisson RLock封装（tryLock/unlock/isHeldByCurrentThread）
  - [ ] 4.8 实现EmergencyDegradeGatewayImpl：紧急降级开关（SETNX/GET/DEL inventory:{skuId}:emergency_degrade）
  - [ ] 4.9 实现InventoryRepositoryImpl：库存CRUD + 自定义UPDATE（含lq非负约束）
  - [ ] 4.10 实现LockOrderRepositoryImpl：锁库存单据CRUD + selectByIdempotentKey(含status) + selectExpiredActive
  - [ ] 4.11 实现DeductionDetailRepositoryImpl：明细CRUD + markPendingAsMerged + calculateNetDeduction(COALESCE)
  - [ ] 4.12 实现RefundDetailRepositoryImpl：回补明细CRUD
  - [ ] 4.13 实现DomainEventPublisherImpl：Spring ApplicationEventPublisher封装
  - [ ] 4.14 创建配置类：RedisConfig、RedissonConfig、MybatisPlusConfig、ThreadPoolConfig、StoreProperties

## Phase 5: App应用层服务编排
- [ ] Task 5: 实现App层应用服务（业务流程编排、事务管理）
  - [ ] 5.1 实现InventoryLockAppService：锁库存流程编排（Step 0幂等检查含status → Step 1 Redis初始化 → Step 2 DB事务 → Step 3 路由更新含重试+补偿）
  - [ ] 5.2 实现InventoryLockAppService：锁库存严格时序保障（Step 0 ARCHIVED状态返回LOCK_ORDER_ALREADY_ARCHIVED）
  - [ ] 5.3 实现InventoryLockAppService：部分锁定逻辑（actualLockQuantity = min(lockQuantity, (sq-lq)*(1-reserveRatio))，reserve-ratio始终生效）
  - [ ] 5.4 实现InventoryLockAppService：Step 3失败处理（重试3次+后台补偿+启动时修复）
  - [ ] 5.5 实现InventoryDeductAppService：扣减流程编排（紧急降级开关检查 → 路由解析 → Redis扣减 → DB明细 → 降级DB扣减）
  - [ ] 5.6 实现InventoryDeductAppService：Redis扣减路径（Lua返回1成功/2分桶耗尽触发异步合并/0不足fallover）
  - [ ] 5.7 实现InventoryDeductAppService：DB降级扣减路径（WHERE sq-lq>=quantity + INSERT MERGED明细）
  - [ ] 5.8 实现InventoryDeductAppService：扣减幂等检查（uk_order_sku唯一索引冲突+INCR回补Redis）
  - [ ] 5.9 实现InventoryMergeAppService：合并提交流程编排（分布式锁 → 扣减屏障 → 先标记后计算 → lq减量更新+非负约束 → 提前释放锁 → Redis清理 → merge_completed）
  - [ ] 5.10 实现InventoryMergeAppService：Step 4a影响0行跳过（幂等保障，避免二次合并lq变负）
  - [ ] 5.11 实现InventoryMergeAppService：COALESCE(SUM, 0)防止NULL
  - [ ] 5.12 实现InventoryRefundAppService：取消流程（PENDING→条件INCR回补Lua / MERGED→wq回补sq+refund_detail）
  - [ ] 5.13 实现InventoryRefundAppService：退款流程（OCCUPIED→oq回补sq+refund_detail含refund_request_id业务幂等）
  - [ ] 5.14 实现InventoryRefundAppService：付款确认流程（MERGED→OCCUPIED，wq→oq）
  - [ ] 5.15 实现AutoLockAppService：自动锁库存（事件去重SETNX + 分布式锁 + max-active限制）
  - [ ] 5.16 实现EmergencyAppService：紧急解锁（逐个合并提交 / SET lq=0+同步ARCHIVED lockOrder+emergency_degrade开关）

## Phase 6: 定时任务与补偿机制
- [ ] Task 6: 实现定时任务和补偿机制
  - [ ] 6.1 实现MergeSchedulerTask：合并提交调度（@Scheduled，扫描ACTIVE lockOrder，检查延迟/分桶耗尽/活跃度衰减触发条件）
  - [ ] 6.2 实现MergeSchedulerTask：分桶耗尽触发（Lua返回2时异步触发合并）
  - [ ] 6.3 实现MergeSchedulerTask：活跃度衰减触发（滑动窗口QPS计数器，INCR deduct_qps:{second_window} TTL=2s）
  - [ ] 6.4 实现CompensateTask：孤立PENDING明细补偿（扫描PENDING+ARCHIVED lockOrder的明细，补偿合并）
  - [ ] 6.5 实现CompensateTask：崩溃恢复（启动时扫描merge_completed=false的ARCHIVED记录，补偿清理Redis分桶）
  - [ ] 6.6 实现CompensateTask：路由缓存补偿修复（扫描5秒前ACTIVE但无路由的lockOrder，补偿更新路由缓存）
  - [ ] 6.7 实现LockExpireTask：锁超时释放（扫描过期ACTIVE lockOrder，触发合并提交+告警）
  - [ ] 6.8 实现AutoLockCheckTask：自动锁库存检测（优先从路由缓存获取活跃lockOrder列表，定期从DB补充）
  - [ ] 6.9 实现AutoLockCheckTask：同步快检事件去重（SETNX auto_lock_pending TTL=5s）

## Phase 7: Adapter适配层
- [ ] Task 7: 实现Adapter层（REST API + CQRS分离）
  - [ ] 7.1 实现InventoryWriteController：写操作入口（锁库存/释放/扣减/取消/退款/确认/合并/紧急解锁）
  - [ ] 7.2 实现InventoryReadController：读操作入口（查询库存/查询明细/查询锁库存单据）
  - [ ] 7.3 实现CmdExe：InventoryLockCmdExe、InventoryDeductCmdExe、InventoryRefundCmdExe、InventoryMergeCmdExe、EmergencyUnlockCmdExe
  - [ ] 7.4 实现QueryExe：InventoryQueryExe、DeductionDetailQueryExe、LockOrderQueryExe
  - [ ] 7.5 实现InventoryAssembler：DTO ↔ Command/Query ↔ Domain对象转换
  - [ ] 7.6 实现统一异常处理器（GlobalExceptionHandler，错误码映射）
  - [ ] 7.7 实现Swagger/OpenAPI接口文档

## Phase 8: Common公共模块
- [ ] Task 8: 实现Common公共模块
  - [ ] 8.1 创建枚举：DeductionStatusEnum、DeductPathEnum、LockOrderStatusEnum、ErrorCodeEnum
  - [ ] 8.2 创建异常：InventoryException、InsufficientStockException、LockQuantityExceededException、MergeCommitFailedException、CompensateMergeFailedException、LockOrderAlreadyArchivedException
  - [ ] 8.3 创建统一返回：Result<T>
  - [ ] 8.4 创建工具类：IdGenerator（雪花算法ID生成器）

## Phase 9: 可观测性
- [ ] Task 9: 实现核心业务指标监控
  - [ ] 9.1 实现MetricsCollector：扣减指标（redis.success.count / redis.fallover.count / redis.degrade.count / redis.degrade.ratio）
  - [ ] 9.2 实现MetricsCollector：合并指标（merge.delay.ms Timer / merge.batch.size DistributionSummary）
  - [ ] 9.3 实现MetricsCollector：锁库存指标（lock.utilization Gauge / lock.expire.count）
  - [ ] 9.4 实现MetricsCollector：补偿指标（redis.compensate.count / reconcile.mismatch.count / compensate.merge.count / compensate.merge.fail.count）
  - [ ] 9.5 实现MetricsCollector：自动锁库存指标（auto-lock.create.count / auto-lock.fail.count）
  - [ ] 9.6 实现MetricsCollector：路由指标（active-lock.route.hit.count / active-lock.route.miss.count）
  - [ ] 9.7 实现MetricsCollector：紧急降级指标（emergency.unlock.count / emergency.degrade.count / merge.crash.recover.count）
  - [ ] 9.8 配置Prometheus采集端点和Grafana仪表盘

## Phase 10: 集成测试
- [ ] Task 10: 编写集成测试
  - [ ] 10.1 测试锁库存流程（严格时序、幂等、ARCHIVED状态检查、部分锁定、Step3失败补偿）
  - [ ] 10.2 测试扣减流程（Redis扣减+返回值2、DB降级、fallover、紧急降级开关、幂等）
  - [ ] 10.3 测试合并提交流程（先标记后计算、lq减量+非负约束、Step4a影响0行跳过、COALESCE、提前释放锁）
  - [ ] 10.4 测试回补流程（PENDING取消条件INCR回补Lua、MERGED取消wq回补、OCCUPIED退款oq回补、refund_request_id业务幂等）
  - [ ] 10.5 测试并发场景（同一SKU并发扣减不超卖、同一lockOrder并发合并提交互斥、PENDING取消与合并提交竞态）
  - [ ] 10.6 测试补偿机制（孤立PENDING明细补偿、崩溃恢复Redis清理、路由缓存补偿修复）
  - [ ] 10.7 测试紧急降级（emergency_degrade开关、SET lq=0+ARCHIVED lockOrder、Redis不可用自动检测）
  - [ ] 10.8 测试Redis Cluster兼容性（Hash Tag Key格式、Lua脚本跨slot验证）

## Phase 11: 性能测试与优化
- [ ] Task 11: 性能压测与调优
  - [ ] 11.1 编写JMeter/Gatling压测脚本（模拟高并发下单）
  - [ ] 11.2 对比纯DB模式 vs Redis模式的TPS差异
  - [ ] 11.3 优化Redis分桶数量和合并窗口期参数
  - [ ] 11.4 验证极端场景下的一致性保障（不超卖不少卖）
  - [ ] 11.5 验证reserve-ratio与min-lock-quantity死区影响

---

# Task Dependencies
- [Task 2] depends on [Task 1] （需要先有基础设施）
- [Task 3] depends on [Task 2] （需要先定义数据模型和PO，理解领域概念）
- [Task 4] depends on [Task 3] （需要先有Domain接口定义才能实现）
- [Task 5] depends on [Task 3, Task 4] （需要Domain模型和Infrastructure实现）
- [Task 6] depends on [Task 5] （需要App层服务就绪）
- [Task 7] depends on [Task 5] （需要App层服务就绪）
- [Task 8] depends on [Task 1] （公共模块可早期创建）
- [Task 9] depends on [Task 5, Task 6] （需要核心链路就绪后埋点）
- [Task 10] depends on [Task 5, Task 6, Task 7] （需要完整链路就绪）
- [Task 11] depends on [Task 10] （测试通过后压测）

**可并行任务**:
- Task 3, Task 8 可在 Task 2 完成后并行开发（Domain模型 + Common枚举/异常）
- Task 6, Task 7 可在 Task 5 完成后并行开发（定时任务 + Controller）
- Task 9 可与 Task 6~7 同步进行（边开发边埋点）

**关键路径**:
Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 10 → Task 11
