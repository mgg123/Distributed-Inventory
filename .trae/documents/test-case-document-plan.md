# 测试用例文档输出计划

## 目标

基于项目设计文档（系统概要设计、系统详细设计、接口设计、数据库设计、时序图、Spec），输出一份完整的测试用例文档，覆盖所有功能模块、异常场景、并发场景和一致性保障机制。

## 输出文件

`store/.trae/specs/redis-inventory-deduction/test/test-cases.md`

## 测试用例文档结构

### 第一部分：文档概述
- 文档信息、版本、编写目的
- 测试范围与约束
- 测试环境要求（Redis Cluster、MySQL 8.x、Spring Boot 4.0.6）
- 测试工具选型（JUnit 5、Mockito、Testcontainers、Spring Boot Test）

### 第二部分：Lua脚本单元测试
覆盖4个Lua脚本的边界值和原子性验证：
1. **deduct.lua** - 扣减脚本测试（正常扣减、余量不足、分桶耗尽返回2、total_remaining防御性检查、KEY不存在场景）
2. **init_buckets.lua** - 初始化脚本测试（正常初始化、原子性验证、重复初始化）
3. **cleanup_buckets.lua** - 清理脚本测试（正常清理、重复清理幂等、KEY不存在场景）
4. **incr_refund.lua** - INCR回补脚本测试（meta有效时回补、meta已失效时跳过、bucket_index有效性）

### 第三部分：锁库存管理模块测试
覆盖锁库存严格时序（Step 0→1→2→3）和异常场景：
1. 正常锁库存流程（Redis初始化→DB事务→路由更新）
2. 锁库存幂等（相同idempotentKey重复请求）
3. ARCHIVED状态幂等冲突（返回LOCK_ORDER_ALREADY_ARCHIVED）
4. 部分锁定（sq-lq < lockQuantity但 >= min-lock-quantity）
5. 可用额度极低（sq-lq < min-lock-quantity）
6. 预留DB降级额度（reserveRatio计算）
7. Step 1 Redis初始化失败（不执行后续步骤）
8. Step 2 DB事务失败（Lua原子清理Redis分桶）
9. Step 3 路由更新失败（重试3次+后台补偿）
10. 锁库存释放（复用合并提交流程）
11. 锁库存超时自动释放
12. reserve-ratio与min-lock-quantity死区验证

### 第四部分：自动锁库存模块测试
覆盖连锁触发机制和滚动管线：
1. 热点品自动触发锁库存
2. 滚动创建新lockOrder（消除空窗期）
3. 同步快检触发（total_remaining低于阈值）
4. 事件去重（SETNX auto_lock_pending）
5. 后台定时任务兜底
6. 活跃lockOrder数量控制（max-active限制）
7. 无可用库存时自动锁库存失败
8. 自动锁库存与手动锁库存兼容

### 第五部分：活跃lockOrder路由模块测试
覆盖路由解析、原子切换和历史兜底：
1. 自动路由到活跃lockOrder
2. 活跃lockOrder原子切换
3. 路由缓存失效兜底（DB重建路由）
4. 活跃路由lockOrder分桶索引失效（历史路由兜底）
5. 历史路由遍历约束（max-history-scan、超时、余量预检）
6. 路由缓存主动清理
7. 历史列表异步清理

### 第六部分：Redis分桶扣减模块测试
覆盖扣减主流程、fallover和降级：
1. 正常扣减流程（合并下单明细，Lua返回1）
2. 分桶耗尽触发合并（Lua返回2）
3. 单桶耗尽fallover
4. 所有桶不足降级DB
5. Redis异常降级DB
6. 紧急降级开关检查
7. 扣减幂等（uk_order_sku唯一索引）
8. DB明细插入失败INCR回补
9. 唯一索引冲突INCR回补
10. DB降级扣减路径（DIRECT_DB，sq-lq约束）

### 第七部分：合并提交模块测试
覆盖先标记后计算、lq减量更新和幂等：
1. 正常合并提交
2. 多lockOrder并存时的合并（lq减量更新）
3. 合并提交重复触发（幂等保障，Step 4a影响0行跳过）
4. 合并提交DB更新部分失败（事务回滚）
5. 合并提交sq不足（WHERE约束触发）
6. 扣减屏障（失效分桶索引，性能优化）
7. 先标记后计算竞态处理
8. COALESCE(SUM, 0)防止NULL
9. 分布式锁提前释放
10. 锁库存主动释放（复用合并提交）

### 第八部分：补偿合并模块测试
覆盖孤立明细和崩溃恢复：
1. 孤立PENDING明细补偿
2. 补偿合并分布式锁
3. 补偿合并sq不足（WHERE约束触发告警）
4. 崩溃恢复（启动时扫描merge_completed=false）
5. 路由缓存补偿修复

### 第九部分：回补管理模块测试
覆盖明细状态机完整流转：
1. PENDING取消（条件INCR回补Lua脚本）
2. PENDING取消时meta已失效（跳过INCR）
3. MERGED取消（wq回补sq + refund_detail）
4. MERGED取消wq不足（告警）
5. OCCUPIED退款（oq回补sq + refund_detail）
6. 部分退款
7. 退款业务幂等（refund_request_id）
8. 付款确认（MERGED→OCCUPIED，wq→oq）
9. 付款确认wq不足
10. PENDING取消与合并提交竞态（3种时序）

### 第十部分：紧急降级模块测试
覆盖Redis不可用场景：
1. 紧急解锁接口（逐个合并提交）
2. 强制解锁（SET lq=0 + 同步ARCHIVED lockOrder）
3. emergency_degrade开关（跳过Redis路径）
4. Redis不可用自动检测（连续超时次数）
5. 预留DB降级额度验证

### 第十一部分：并发场景测试
覆盖高并发下的一致性保障：
1. 同一SKU并发锁库存（SQL行锁防护）
2. 同一SKU并发扣减（Redis Lua原子操作）
3. 同一SKU并发DB降级扣减（SQL行锁防护）
4. 同一lockOrder并发合并提交（分布式锁互斥）
5. PENDING取消与合并提交竞态
6. 同一订单重复扣减（唯一索引）
7. 同一幂等键重复锁库存（唯一索引）
8. 多lockOrder并存合并（lq减量更新+非负约束）
9. 二次合并触发（Step 4a影响0行跳过）

### 第十二部分：数据一致性验证测试
覆盖防超卖和防少卖机制：
1. Redis层防超卖（Lua原子检查+扣减）
2. DB层防超卖（WHERE sq-lq >= quantity）
3. 合并提交最终防线（WHERE sq >= net_deduction AND lq >= currentLockQuantity）
4. 四字段非负约束（CHECK约束验证）
5. 扣减明细唯一索引防重复
6. PENDING取消防少卖（条件INCR回补）
7. DB明细插入失败防少卖（INCR回补）
8. 合并提交竞态防少卖（先标记后计算）
9. 孤立PENDING明细防少卖（补偿合并）
10. lq与lock_inventory_order一致性

### 第十三部分：Redis Cluster兼容性测试
覆盖Hash Tag和跨slot验证：
1. Hash Tag Key格式验证
2. 同一lockOrder的Key在同一slot
3. 同一skuId的路由Key在同一slot
4. Lua脚本跨slot执行验证

### 第十四部分：可观测性测试
覆盖监控指标采集：
1. 扣减指标（redis.success.count / fallover.count / degrade.count / degrade.ratio）
2. 合并指标（merge.delay.ms / merge.batch.size）
3. 锁库存指标（lock.utilization / lock.expire.count）
4. 补偿指标（compensate.merge.count / compensate.merge.fail.count）
5. 自动锁库存指标（auto-lock.create.count / auto-lock.fail.count）
6. 路由指标（route.hit.count / route.miss.count）
7. 紧急降级指标（emergency.unlock.count / emergency.degrade.count）
8. 崩溃恢复指标（merge.crash.recover.count）

### 第十五部分：API接口测试
覆盖所有REST API接口：
1. POST /api/v1/inventory/lock（锁库存）
2. POST /api/v1/inventory/lock/{lockOrderId}/release（释放锁库存）
3. POST /api/v1/inventory/deduct（扣减库存）
4. GET /api/v1/inventory/{skuId}（查询库存）
5. POST /api/v1/inventory/merge（手动触发合并）
6. POST /api/v1/inventory/confirm（付款确认）
7. POST /api/v1/inventory/cancel（取消订单）
8. POST /api/v1/inventory/refund（退款）
9. POST /api/v1/inventory/emergency-unlock（紧急解锁）
10. GET /api/v1/inventory/deduction/{orderId}（查询扣减明细）
11. GET /api/v1/inventory/lock-order/{lockOrderId}（查询锁库存单据）

### 第十六部分：端到端场景测试
覆盖完整业务链路：
1. 完整链路：锁库存→扣减→合并→付款确认→退款
2. 完整链路：锁库存→扣减→合并→取消
3. 完整链路：自动锁库存→扣减→合并→滚动管线
4. 完整链路：Redis降级→DB扣减→付款确认→退款
5. 完整链路：紧急降级→恢复→重新锁库存

## 实施步骤

1. 创建测试用例文档目录 `store/.trae/specs/redis-inventory-deduction/test/`
2. 编写测试用例文档 `test-cases.md`，每个测试用例包含：
   - 用例编号（模块-类型-序号，如 LOCK-FUNC-001）
   - 用例名称
   - 前置条件
   - 测试步骤
   - 预期结果
   - 优先级（P0/P1/P2）
   - 测试类型（功能/并发/异常/性能/一致性）
3. 确保覆盖所有设计文档中的Scenario和异常场景处理矩阵
