# Tasks - 基于Redis分布式强一致库存扣减系统

## Phase 1: 基础设施搭建
- [ ] Task 1: 项目依赖配置与基础架构搭建
  - [ ] 1.1 添加Redisson依赖到pom.xml（redisson-spring-boot-starter）
  - [ ] 1.2 添加MyBatis-Plus依赖（mybatis-plus-spring-boot3-starter）
  - [ ] 1.3 添加MySQL Connector和数据库连接池配置（HikariCP）
  - [ ] 1.4 配置Redisson连接参数（单机/集群模式）和MySQL数据源信息
  - [ ] 1.5 创建基础包结构（controller/service/serviceImpl/mapper/model/config/dto/enums/scheduler）
  - [ ] 1.6 配置Micrometer + Prometheus监控依赖和Actuator端点

## Phase 2: 数据模型设计
- [ ] Task 2: 设计并实现库存核心数据模型（基于MyBatis-Plus）
  - [ ] 2.1 创建Inventory实体类（@TableName("inventory")，包含sq/wq/oq/lq字段及@TableId、@TableField注解）
  - [ ] 2.2 创建DeductionDetail实体类（包含orderId必填、merge_batch_id、5状态枚举PENDING/MERGED/OCCUPIED/CANCELLED/REFUNDED）
  - [ ] 2.3 创建LockOrder实体类（锁库存单据，含过期时间字段expireAt、skuId、lockQuantity）
  - [ ] 2.4 设计数据库DDL脚本（inventory表含CHECK约束、deduction_detail表含merge_batch_id索引、lock_order表）
  - [ ] 2.5 实现Mapper接口继承BaseMapper<T>，提供自定义查询方法（含sq-lq>=lockQuantity的自定义UPDATE）

## Phase 3: Redis分桶管理模块（基于Redisson per-lockOrder分桶）
- [ ] Task 3: 实现Redis per-lockOrder分桶初始化与管理
  - [ ] 3.1 定义分桶策略接口与实现（per-lockOrder分桶设计，Key格式 inventory:lock:{lockOrderId}:bucket:{n}，同一SKU的不同lockOrder互不干扰）
  - [ ] 3.2 使用Redisson的RBucket存储分桶索引元数据（inventory:lock:{lockOrderId}:meta，含桶数量、skuId、各桶Key）
  - [ ] 3.3 实现分桶初始化逻辑（均匀分配锁定量到N个桶，N通过store.bucket.count配置，默认16）
  - [ ] 3.4 编写Lua脚本实现原子check-and-decr扣减（防止DECR后计数器变负，返回1成功/0不足）
  - [ ] 3.5 实现随机选择+单桶耗尽fallover路由策略（随机选桶→Lua扣减→不足时fallover其他桶，最多M次默认3次）
  - [ ] 3.6 编写单元测试验证分桶分配正确性、Lua脚本原子性、fallover路由逻辑

## Phase 4: 锁库存模块
- [ ] Task 4: 实现锁库存核心功能（含并发控制和释放机制）
  - [ ] 4.1 实现LockService接口与实现类
  - [ ] 4.2 完成DB层lq字段更新操作（UPDATE SET lq = lq + #{lockQuantity} WHERE id = #{skuId} AND sq - lq >= #{lockQuantity}）
  - [ ] 4.3 实现应用层预校验（锁库存前先查询sq - lq值，小于lockQuantity直接返回LOCK_QUANTITY_EXCEEDED）
  - [ ] 4.4 实现先Redis后DB策略（先幂等初始化per-lockOrder分桶，再写DB；DB失败时清理Redis分桶）
  - [ ] 4.5 创建锁库存单据（含过期时间expireAt）
  - [ ] 4.6 实现锁库存主动释放接口（复用合并提交流程：sq只减实际卖出量，lq重置为0）
  - [ ] 4.7 定义LOCK_QUANTITY_EXCEEDED错误码和统一错误响应
  - [ ] 4.8 编写集成测试验证锁库存流程（并发锁库存、可售量不足、主动释放）

## Phase 5: Redis预扣减模块（基于Redisson RScript Lua脚本）
- [ ] Task 5: 实现基于Lua脚本的高并发扣减逻辑
  - [ ] 5.1 使用Redisson RScript执行Lua脚本实现原子check-and-decr扣减操作
  - [ ] 5.2 实现单桶耗尽fallover逻辑（Lua返回0时，从该lockOrder其他桶中随机选择重试，最多M次）
  - [ ] 5.3 实现Redis异常降级机制（捕获RedisException/超时，自动切换DB直接扣减模式）
  - [ ] 5.4 实现Redis回补机制（DB明细插入失败时，INCR回补对应分桶计数）
  - [ ] 5.5 封装DeductionController协调Redis预扣减→DB明细插入→失败回补流程
  - [ ] 5.6 编写并发测试验证线程安全性（CountDownLatch模拟高并发，验证不超卖）

## Phase 6: DB扣减明细模块（基于MyBatis-Plus IService + 完整状态机）
- [ ] Task 6: 实现扣减明细记录与状态机管理
  - [ ] 6.1 实现DeductionDetailServiceImpl（继承IService<DeductionDetail>，提供插入/查询/更新明细状态）
  - [ ] 6.2 定义DetailStatus枚举（PENDING/MERGED/OCCUPIED/CANCELLED/REFUNDED）及状态转换规则
  - [ ] 6.3 实现明细状态机转换逻辑（PENDING→MERGED、PENDING→CANCELLED、MERGED→OCCUPIED、MERGED→CANCELLED+wq回补sq、OCCUPIED→REFUNDED+oq回补sq）
  - [ ] 6.4 实现幂等性检查（基于orderId的LambdaQueryWrapper去重查询，orderId为必填字段）
  - [ ] 6.5 实现merge_batch_id幂等防护（合并时填充，WHERE条件带merge_batch_id IS NULL）
  - [ ] 6.6 实现Redis回补机制（明细插入失败时调用RAtomicLong.incrementAndGet()回滚）
  - [ ] 6.7 支持批量查询待合并明细（按lockOrderId聚合PENDING状态且merge_batch_id IS NULL的记录）
  - [ ] 6.8 编写单元测试验证明细状态机流转、幂等防护、Redis回补正确性

## Phase 7: 合并提交模块（基于Spring @Scheduled + Redisson RLock + @Transactional）
- [ ] Task 7: 实现异步合并提交调度器（含幂等和事务保障）
  - [ ] 7.1 使用@Scheduled(fixedDelayString = "${store.merge.delay-ms:1000}", initialDelay = 1000)实现定时合并任务
  - [ ] 7.2 使用Redisson RLock分布式锁（key=merge:{lockOrderId}）保障合并互斥性
  - [ ] 7.3 实现合并时失效该lockOrder的per-lockOrder分桶（不影响同一SKU的其他lockOrder）
  - [ ] 7.4 实现merge_batch_id分配逻辑（全局唯一，合并时填充到明细记录）
  - [ ] 7.5 在@Transactional事务内执行合并操作：
    - SELECT明细 WHERE status='PENDING' AND merge_batch_id IS NULL
    - SUM(扣减数量) - SUM(回补数量) = 净扣减值
    - UPDATE inventory SET sq = sq - 净扣减值, wq = wq + 净扣减值, lq = 0 WHERE id = 商品ID
    - UPDATE deduction_detail SET status='MERGED', merge_batch_id = #{batchId} WHERE lock_order_id = #{lockOrderId} AND status='PENDING' AND merge_batch_id IS NULL
  - [ ] 7.6 合并完成后清零/删除该lockOrder对应的Redis分桶
  - [ ] 7.7 事务失败时整体回滚（sq/wq不变，明细仍为PENDING），下次调度重试
  - [ ] 7.8 编写端到端测试验证完整合并流程（锁库存→多次扣减→合并→验证sq/wq/lq值正确）

## Phase 8: 锁超时释放与对账保障
- [ ] Task 8: 实现锁超时自动释放和对账任务
  - [ ] 8.1 实现LockExpireCleaner定时任务（@Scheduled扫描超过expireAt的锁库存单据）
  - [ ] 8.2 超时单据自动触发合并提交流程释放库存（lq重置为0）并发出告警通知
  - [ ] 8.3 实现Reconcile对账任务（定时检测lq与Redis各桶sum是否一致）
  - [ ] 8.4 对账不一致时记录告警并输出store.reconcile.mismatch.count指标
  - [ ] 8.5 实现合并期间分桶轮转策略（旧桶失效后新桶初始化，不影响新扣减请求）
  - [ ] 8.6 编写测试验证超时释放和对账逻辑

## Phase 9: 一致性保障与异常处理
- [ ] Task 9: 完善异常场景补偿机制
  - [ ] 9.1 实现全局异常处理器（统一错误码和日志）
  - [ ] 9.2 实现重试机制（合并失败自动重试+指数退避）
  - [ ] 9.3 实现Redis→DB降级链路完整闭环（超时/异常/全部桶不足→DB直接扣减）
  - [ ] 9.4 实现约束层级：SQL层硬约束（sq-lq>=lockQuantity）+ 应用层软校验（预查询快速失败）
  - [ ] 9.5 编写异常场景测试用例（Redis宕机、DB超时、合并事务部分失败、并发合并等）

## Phase 10: 可观测性（Micrometer + Prometheus）
- [ ] Task 10: 实现核心业务指标监控
  - [ ] 10.1 实现扣减指标：store.deduct.redis.success.count、store.deduct.redis.fallover.count、store.deduct.redis.degrade.count
  - [ ] 10.2 实现降级比例指标：store.deduct.redis.degrade.ratio（Gauge，降级数/总扣减数）
  - [ ] 10.3 实现合并指标：store.merge.delay.ms（Timer）、store.merge.batch.size（DistributionSummary）
  - [ ] 10.4 实现锁库存指标：store.lock.utilization（Gauge，实际扣减量/lq锁定量）、store.lock.expire.count
  - [ ] 10.5 实现补偿指标：store.redis.compensate.count、store.reconcile.mismatch.count
  - [ ] 10.6 配置Prometheus采集端点和Grafana仪表盘

## Phase 11: API接口与文档
- [ ] Task 11: 对外暴露完整的RESTful API
  - [ ] 11.1 锁库存接口：POST /api/inventory/lock
  - [ ] 11.2 释放锁库存接口：POST /api/inventory/lock/{lockOrderId}/release
  - [ ] 11.3 扣减库存接口：POST /api/inventory/deduct
  - [ ] 11.4 查询库存接口：GET /api/inventory/{skuId}
  - [ ] 11.5 手动触发合并接口：POST /api/inventory/merge（运维用）
  - [ ] 11.6 使用Swagger/OpenAPI生成接口文档

## Phase 12: 性能测试与优化
- [ ] Task 12: 性能压测与调优
  - [ ] 12.1 编写JMeter/Gatling压测脚本（模拟高并发下单）
  - [ ] 12.2 对比纯DB模式 vs Redis模式的TPS差异
  - [ ] 12.3 优化Redis分桶数量和合并窗口期参数
  - [ ] 12.4 验证极端场景下的一致性保障（不超卖不少卖）

---

# Task Dependencies
- [Task 2] depends on [Task 1] （需要先有基础设施）
- [Task 3] depends on [Task 2] （需要先定义数据模型）
- [Task 4] depends on [Task 3] （需要先有分桶能力）
- [Task 5] depends on [Task 4, Task 6] （需要先能锁定库存和记录明细）
- [Task 6] depends on [Task 2] （需要先有明细表结构）
- [Task 7] depends on [Task 5, Task 6] （需要有扣减数据和明细才能合并）
- [Task 8] depends on [Task 4, Task 7] （需要锁库存单据和合并流程就绪）
- [Task 9] depends on [Task 5, Task 6, Task 7] （需要在主流程完成后完善异常处理）
- [Task 10] depends on [Task 5, Task 7, Task 8] （需要核心链路就绪后埋点监控）
- [Task 11] depends on [Task 4, Task 5, Task 7] （核心功能就绪后暴露接口）
- [Task 12] depends on [Task 11] （所有功能完成后进行压测）

**可并行任务**:
- Task 3, Task 4 可在 Task 2 完成后并行开发
- Task 8, Task 9 的部分子任务可与 Task 7 同步进行
- Task 10 可与 Task 7~9 同步进行（边开发边埋点）
