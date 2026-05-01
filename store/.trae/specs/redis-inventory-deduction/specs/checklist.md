# Checklist - 基于Redis分布式强一致库存扣减系统

## 基础设施验证
- [ ] 项目能够正常启动（Spring Boot应用启动无报错）
- [ ] Redis连接配置正确且能够成功ping通
- [ ] MySQL数据库连接配置正确且能够执行查询
- [ ] 基础包结构符合分层架构规范（controller/service/repository/model/config/scheduler）
- [ ] Micrometer + Prometheus依赖已配置，Actuator端点可访问

## 数据模型验证
- [ ] Inventory实体包含所有必要字段（id/skuId/sq/wq/oq/lq及@TableId、@TableField注解）
- [ ] DeductionDetail实体包含orderId必填字段、merge_batch_id字段、5状态枚举（PENDING/MERGED/OCCUPIED/CANCELLED/REFUNDED）
- [ ] LockOrder实体包含过期时间expireAt字段、skuId、lockQuantity
- [ ] 数据库DDL脚本执行成功，三张表创建完毕（inventory含CHECK约束、deduction_detail含merge_batch_id索引、lock_order表）
- [ ] Mapper提供自定义UPDATE方法（含sq-lq>=lockQuantity条件的SQL）

## Redis per-lockOrder分桶功能验证
- [ ] 分桶Key格式为 inventory:lock:{lockOrderId}:bucket:{n}（per-lockOrder独立分桶，同一SKU的不同lockOrder互不干扰）
- [ ] 分桶索引元数据存储在 inventory:lock:{lockOrderId}:meta（含桶数量、skuId、各桶Key）
- [ ] 分桶初始化将锁定量均匀分配到N个桶（N通过store.bucket.count配置，默认16，误差≤1）
- [ ] Lua脚本原子扣减：check-and-decr在单次操作内完成，返回1成功/0不足
- [ ] 随机选择+单桶耗尽fallover策略：随机选桶→Lua扣减→不足时fallover其他桶（最多M次，默认3次）
- [ ] 合并失效该lockOrder的per-lockOrder分桶不影响同一SKU的其他lockOrder的扣减能力
- [ ] 单元测试覆盖：正常分配、Lua脚本原子性、fallover路由、边界值、空值处理

## 锁库存功能验证
- [ ] 调用lock接口后，DB的lq字段增加而sq字段不变
- [ ] SQL条件为 `WHERE sq - lq >= #{lockQuantity}`（非 > 0），防止并发超锁
- [ ] 应用层预校验：锁库存前查询sq-lq值，小于lockQuantity直接返回LOCK_QUANTITY_EXCEEDED
- [ ] 先Redis后DB策略：先幂等初始化per-lockOrder分桶，再写DB；DB失败时清理Redis分桶
- [ ] 返回的锁库存单据ID全局唯一，含过期时间expireAt
- [ ] 锁库存主动释放：复用合并提交流程，sq只减实际卖出量，lq重置为0
- [ ] 锁库存失败时返回LOCK_QUANTITY_EXCEEDED错误码
- [ ] 集成测试通过：并发锁库存不超锁、可售量不足快速失败、主动释放正确

## Redis预扣减功能验证（Lua脚本+Fallover+降级）
- [ ] Lua脚本扣减返回1时为成功，返回0时为当前桶余量不足（非DECR后负值）
- [ ] Lua脚本不会产生DECR后计数器变负的问题（原子check-and-decr）
- [ ] 单桶耗尽时自动fallover到其他桶重试（最多M次）
- [ ] Redis超时/异常时自动降级到DB直接扣减模式
- [ ] DB明细插入失败时Redis对应分桶被正确回补（INCR恢复计数）
- [ ] 扣减控制器严格遵循"Lua扣减→DB明细→失败回补"的顺序执行
- [ ] 并发测试：1000线程同时扣减不会出现超卖现象

## DB扣减明细功能验证（5状态状态机+幂等防护）
- [ ] Redis扣减成功后立即插入一条PENDING状态的明细记录
- [ ] orderId为必填字段，相同orderId重复调用时触发幂等保护
- [ ] 明细状态机5状态完整：PENDING→MERGED、PENDING→CANCELLED、MERGED→OCCUPIED、MERGED→CANCELLED（wq回补sq）、OCCUPIED→REFUNDED（oq回补sq）
- [ ] merge_batch_id字段在合并时填充，防止同一明细被重复合并
- [ ] 明细插入失败时Redis库存被正确回补（INCR恢复对应分桶计数）
- [ ] 支持按lockOrderId批量查询PENDING且merge_batch_id IS NULL的待合并明细
- [ ] 状态机转换测试：每种转换路径均有单元测试覆盖

## 合并提交功能验证（分布式锁+事务+幂等）
- [ ] 定时任务在延迟窗口期后正确触发（store.merge.delay-ms可配置，默认1000ms）
- [ ] 合并前获取Redisson RLock分布式锁（key=merge:{lockOrderId}），保证互斥
- [ ] 合并时失效该lockOrder的per-lockOrder分桶（不影响同一SKU的其他lockOrder）
- [ ] 分配全局唯一merge_batch_id
- [ ] 净扣减计算准确：SUM(扣减) - SUM(回补)
- [ ] @Transactional事务内原子执行：sq减少+wq增加+lq重置为0 + 明细状态更新为MERGED
- [ ] WHERE条件带merge_batch_id IS NULL，防止已合并记录被重复处理
- [ ] 事务失败整体回滚（sq/wq不变，明细仍为PENDING），下次调度重试
- [ ] 合并完成后Redis分桶清零/删除
- [ ] 重复触发合并时幂等跳过（分布式锁+merge_batch_id双重防护）
- [ ] 端到端测试：锁库存→多次扣减→合并→验证sq/wq/lq值正确

## 锁超时释放与对账验证
- [ ] LockExpireCleaner定时扫描超过expireAt的锁库存单据
- [ ] 超时单据自动触发合并提交流程释放库存（lq重置为0）
- [ ] 超时释放同时发出告警通知
- [ ] Reconcile对账任务定时检测lq与Redis各桶sum是否一致
- [ ] 对账不一致时记录告警并输出store.reconcile.mismatch.count指标
- [ ] 合并期间分桶轮转策略正常工作（旧桶失效后新桶初始化）

## 异常处理与一致性验证
- [ ] Redis完全不可用时系统能够优雅降级（走DB模式）
- [ ] 合并提交事务部分失败时整体回滚，下次调度重试
- [ ] 合并失败自动重试（指数退避策略）
- [ ] 约束层级正确：SQL层硬约束（sq-lq>=lockQuantity）+ 应用层软校验（预查询快速失败）
- [ ] 统一错误码体系（LOCK_QUANTITY_EXCEEDED等）
- [ ] 极端场景测试：模拟网络分区/节点宕机后数据最终一致

## 可观测性验证
- [ ] store.deduct.redis.success.count Counter正常上报
- [ ] store.deduct.redis.fallover.count Counter正常上报
- [ ] store.deduct.redis.degrade.count Counter正常上报
- [ ] store.deduct.redis.degrade.ratio Gauge正常计算
- [ ] store.merge.delay.ms Timer正常记录
- [ ] store.merge.batch.size DistributionSummary正常记录
- [ ] store.lock.utilization Gauge正常计算（实际扣减量/lq锁定量）
- [ ] store.lock.expire.count Counter正常上报
- [ ] store.redis.compensate.count Counter正常上报
- [ ] store.reconcile.mismatch.count Counter正常上报
- [ ] Prometheus采集端点可访问，Grafana仪表盘配置完成

## API接口验证
- [ ] POST /api/inventory/lock 接口文档完整且可调用
- [ ] POST /api/inventory/lock/{lockOrderId}/release 释放锁库存接口可调用
- [ ] POST /api/inventory/deduct 接口文档完整且可调用
- [ ] GET /api/inventory/{skuId} 返回准确的当前库存信息
- [ ] POST /api/inventory/merge 能够手动触发合并（仅限运维）
- [ ] Swagger UI页面可访问且展示所有接口定义
- [ ] 接口响应格式统一（包含code/message/data标准结构）

## 性能指标验证
- [ ] 压测报告显示Redis模式下TPS相比纯DB提升≥5倍
- [ ] P99延迟控制在合理范围内（如≤100ms）
- [ ] 长时间运行无内存泄漏（GC正常）
- [ ] 高并发下不出现超卖或少卖现象（数据一致性100%）
- [ ] 参数调优建议文档已输出（最佳分桶数、合并窗口期等）

---

## 验收标准总结
✅ **功能性**: 所有核心流程（锁库存→扣减→合并→释放）正常运行
✅ **一致性**: 不超卖、不少卖、幂等性保障完备（分布式锁+merge_batch_id+@Transactional）
✅ **可用性**: Redis故障时自动降级，核心链路可用率≥99.9%
✅ **可观测性**: 10项核心业务指标正常上报，Prometheus+Grafana监控就绪
✅ **性能**: 热点扣防TPS满足业务需求（≥5000 QPS参考值）
✅ **可维护性**: 监控告警完善、接口文档齐全、代码覆盖率≥80%
