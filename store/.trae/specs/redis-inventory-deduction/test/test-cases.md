# 基于Redis分布式强一致库存扣减系统 — 测试用例文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 系统名称 | 基于Redis分布式强一致库存扣减系统 |
| 文档版本 | V1.0 |
| 编写日期 | 2026-05-03 |
| 关联设计文档 | 系统概要设计V2.0、系统详细设计V2.0、接口设计V2.0、数据库设计V2.0、时序图V2.0、Spec V6.0 |

## 1 文档概述

### 1.1 编写目的

本文档基于项目设计文档，系统化输出所有测试用例，覆盖功能测试、异常测试、并发测试、一致性验证和端到端场景测试，为测试执行和验收提供直接依据。

### 1.2 测试范围

| 范围 | 说明 |
|------|------|
| 包含 | 锁库存管理、自动锁库存、路由模块、Redis分桶扣减、合并提交、补偿合并、回补管理、紧急降级、可观测性、API接口 |
| 不包含 | Redis/MySQL基础设施运维测试、热点识别算法测试、订单/支付系统测试 |

### 1.3 测试环境要求

| 项目 | 要求 |
|------|------|
| 应用框架 | Spring Boot 4.0.6 |
| Redis | Redis 7.x Cluster（3主3从），Hash Tag兼容 |
| MySQL | MySQL 8.x InnoDB |
| 测试工具 | JUnit 5 + Mockito + Testcontainers + Spring Boot Test |

### 1.4 用例编号规范

格式：`{模块缩写}-{类型缩写}-{序号}`

| 模块缩写 | 模块名称 |
|----------|----------|
| LUA | Lua脚本 |
| LOCK | 锁库存管理 |
| AUTO | 自动锁库存 |
| ROUTE | 活跃lockOrder路由 |
| DEDUCT | Redis分桶扣减 |
| MERGE | 合并提交 |
| COMP | 补偿合并 |
| REFUND | 回补管理 |
| EMER | 紧急降级 |
| CONC | 并发场景 |
| CONSIS | 数据一致性 |
| CLUSTER | Redis Cluster兼容 |
| METRIC | 可观测性 |
| API | API接口 |
| E2E | 端到端场景 |

| 类型缩写 | 测试类型 |
|----------|----------|
| FUNC | 功能测试 |
| EXCP | 异常测试 |
| CONC | 并发测试 |
| PERF | 性能测试 |
| CONS | 一致性测试 |

### 1.5 优先级定义

| 优先级 | 说明 |
|--------|------|
| P0 | 核心链路，阻塞性，必须通过 |
| P1 | 重要功能，影响业务正确性 |
| P2 | 边界场景、异常兜底、可观测性 |

---

## 2 Lua脚本单元测试

### 2.1 deduct.lua 扣减脚本

| 用例编号 | LUA-FUNC-001 |
|----------|-------------|
| 用例名称 | 正常扣减-桶余量和total_remaining充足 |
| 前置条件 | bucket:0 = 100, total_remaining = 1000 |
| 测试步骤 | 1. 执行deduct.lua，KEYS=[bucket:0, total_remaining]，ARGV=[10] |
| 预期结果 | 返回1；bucket:0 = 90；total_remaining = 990 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-FUNC-002 |
|----------|-------------|
| 用例名称 | 扣减成功且分桶耗尽-返回2 |
| 前置条件 | bucket:0 = 10, total_remaining = 10 |
| 测试步骤 | 1. 执行deduct.lua，KEYS=[bucket:0, total_remaining]，ARGV=[10] |
| 预期结果 | 返回2；bucket:0 = 0；total_remaining = 0 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-FUNC-003 |
|----------|-------------|
| 用例名称 | 桶余量不足-返回0 |
| 前置条件 | bucket:0 = 5, total_remaining = 1000 |
| 测试步骤 | 1. 执行deduct.lua，KEYS=[bucket:0, total_remaining]，ARGV=[10] |
| 预期结果 | 返回0；bucket:0 = 5（不变）；total_remaining = 1000（不变） |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-FUNC-004 |
|----------|-------------|
| 用例名称 | total_remaining不足-返回0（防御性检查） |
| 前置条件 | bucket:0 = 100, total_remaining = 5 |
| 测试步骤 | 1. 执行deduct.lua，KEYS=[bucket:0, total_remaining]，ARGV=[10] |
| 预期结果 | 返回0；bucket:0 = 100（不变）；total_remaining = 5（不变） |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-EXCP-001 |
|----------|-------------|
| 用例名称 | KEY不存在-桶和total_remaining均不存在 |
| 前置条件 | bucket:0和total_remaining Key均不存在 |
| 测试步骤 | 1. 执行deduct.lua，KEYS=[bucket:0, total_remaining]，ARGV=[10] |
| 预期结果 | 返回0（GET返回nil转为0，0 < 10） |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

| 用例编号 | LUA-FUNC-005 |
|----------|-------------|
| 用例名称 | 扣减数量为1-最小单位扣减 |
| 前置条件 | bucket:0 = 1, total_remaining = 1 |
| 测试步骤 | 1. 执行deduct.lua，KEYS=[bucket:0, total_remaining]，ARGV=[1] |
| 预期结果 | 返回2（total_remaining减至0）；bucket:0 = 0；total_remaining = 0 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-FUNC-006 |
|----------|-------------|
| 用例名称 | 扣减后total_remaining为正数-返回1 |
| 前置条件 | bucket:0 = 100, total_remaining = 20 |
| 测试步骤 | 1. 执行deduct.lua，KEYS=[bucket:0, total_remaining]，ARGV=[10] |
| 预期结果 | 返回1；bucket:0 = 90；total_remaining = 10 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

### 2.2 init_buckets.lua 初始化脚本

| 用例编号 | LUA-FUNC-007 |
|----------|-------------|
| 用例名称 | 正常初始化16个分桶+meta+total_remaining |
| 前置条件 | 所有Key不存在 |
| 测试步骤 | 1. 执行init_buckets.lua，KEYS=[bucket:0..15, meta, total_remaining]，ARGV=[各桶初始值, meta JSON, total_remaining值] |
| 预期结果 | 返回16；所有bucket Key已SET；meta Key已SET；total_remaining Key已SET |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-FUNC-008 |
|----------|-------------|
| 用例名称 | 原子性验证-初始化全部成功或全部不初始化 |
| 前置条件 | 所有Key不存在 |
| 测试步骤 | 1. 执行init_buckets.lua正常初始化；2. 验证所有Key均已创建 |
| 预期结果 | 所有Key均存在且值正确 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-EXCP-002 |
|----------|-------------|
| 用例名称 | 重复初始化-覆盖已有值 |
| 前置条件 | bucket:0 = 100（旧值） |
| 测试步骤 | 1. 执行init_buckets.lua，ARGV中bucket:0新值为50 |
| 预期结果 | bucket:0 = 50（被覆盖） |
| 优先级 | P2 |
| 测试类型 | 功能测试 |

### 2.3 cleanup_buckets.lua 清理脚本

| 用例编号 | LUA-FUNC-009 |
|----------|-------------|
| 用例名称 | 正常清理所有分桶+meta+total_remaining |
| 前置条件 | 16个bucket Key + meta Key + total_remaining Key均存在 |
| 测试步骤 | 1. 执行cleanup_buckets.lua，KEYS=[bucket:0..15, meta, total_remaining] |
| 预期结果 | 返回1；所有Key已被DEL |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-EXCP-003 |
|----------|-------------|
| 用例名称 | 重复清理幂等-Key已不存在 |
| 前置条件 | 所有Key已被清理 |
| 测试步骤 | 1. 再次执行cleanup_buckets.lua |
| 预期结果 | 返回1；DEL不存在的Key返回0，不影响正确性 |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

### 2.4 incr_refund.lua INCR回补脚本

| 用例编号 | LUA-FUNC-010 |
|----------|-------------|
| 用例名称 | meta有效时INCR回补成功 |
| 前置条件 | meta Key存在，bucket:3 = 50，total_remaining = 500 |
| 测试步骤 | 1. 执行incr_refund.lua，KEYS=[meta, bucket:3, total_remaining]，ARGV=[10] |
| 预期结果 | 返回1；bucket:3 = 60；total_remaining = 510 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-FUNC-011 |
|----------|-------------|
| 用例名称 | meta已失效时跳过INCR回补 |
| 前置条件 | meta Key已被DEL（合并提交时失效），bucket:3和total_remaining仍存在 |
| 测试步骤 | 1. 执行incr_refund.lua，KEYS=[meta, bucket:3, total_remaining]，ARGV=[10] |
| 预期结果 | 返回0；bucket:3 = 50（不变）；total_remaining = 500（不变） |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LUA-EXCP-004 |
|----------|-------------|
| 用例名称 | bucket_index超出有效范围-INCR作用于不存在的Key |
| 前置条件 | meta Key存在，bucket:99不存在 |
| 测试步骤 | 1. 执行incr_refund.lua，KEYS=[meta, bucket:99, total_remaining]，ARGV=[10] |
| 预期结果 | 应用层应在调用前校验bucket_index有效性（0 <= bucketIndex < bucketCount），超出范围跳过INCR并告警 |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

---

## 3 锁库存管理模块测试

| 用例编号 | LOCK-FUNC-001 |
|----------|-------------|
| 用例名称 | 正常锁库存-完整严格时序Step 0→1→2→3 |
| 前置条件 | inventory: skuId=10001, sq=20000, wq=0, oq=0, lq=0 |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=10000, idempotentKey="lock-001") |
| 预期结果 | Step 0: 幂等检查无已存在记录；Step 1: Redis 16个分桶初始化成功，每桶625，total_remaining=10000；Step 2: DB lq=9000（10000*(1-0.1)），lock_inventory_order插入成功(status=ACTIVE)；Step 3: 路由缓存inventory:active_lock:10001已更新；返回lockOrderId |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-002 |
|----------|-------------|
| 用例名称 | 锁库存幂等-相同idempotentKey重复请求 |
| 前置条件 | 已存在lockOrder(idempotentKey="lock-001", status=ACTIVE) |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=10000, idempotentKey="lock-001") |
| 预期结果 | Step 0检测到已存在ACTIVE记录，直接返回已有lockOrderId；不重复增加lq；不重复创建Redis分桶 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-003 |
|----------|-------------|
| 用例名称 | ARCHIVED状态幂等冲突-返回LOCK_ORDER_ALREADY_ARCHIVED |
| 前置条件 | 已存在lockOrder(idempotentKey="lock-001", status=ARCHIVED) |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=10000, idempotentKey="lock-001") |
| 预期结果 | Step 0检测到ARCHIVED状态，返回错误码LOCK_ORDER_ALREADY_ARCHIVED |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-004 |
|----------|-------------|
| 用例名称 | 部分锁定-sq-lq不足lockQuantity但>=min-lock-quantity |
| 前置条件 | inventory: sq=5000, lq=4500, sq-lq=500 |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=1000, idempotentKey="lock-002") |
| 预期结果 | actualLockQuantity = min(1000, 500*(1-0.1)) = min(1000, 450) = 450；Redis分桶初始化450；DB lq增加450；返回actualLockQuantity=450 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-005 |
|----------|-------------|
| 用例名称 | 可用额度极低-低于min-lock-quantity |
| 前置条件 | inventory: sq=5099, lq=5000, sq-lq=99（< min-lock-quantity=100） |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=1000, idempotentKey="lock-003") |
| 预期结果 | 返回错误码LOCK_QUANTITY_EXCEEDED；不创建Redis分桶；不更新路由缓存 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-006 |
|----------|-------------|
| 用例名称 | 预留DB降级额度-reserveRatio计算 |
| 前置条件 | inventory: sq=20000, lq=0, reserveRatio=0.1 |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=10000, idempotentKey="lock-004") |
| 预期结果 | actualLockQuantity = min(10000, 20000*(1-0.1)) = min(10000, 18000) = 10000；reservedQuantity = 20000*0.1 = 2000；DB lq=10000；sq-lq=10000（DB降级可用额度） |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-007 |
|----------|-------------|
| 用例名称 | reserveRatio=0-锁定全部额度 |
| 前置条件 | inventory: sq=10000, lq=0, reserveRatio=0 |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=10000, idempotentKey="lock-005", reserveRatio=0) |
| 预期结果 | actualLockQuantity = min(10000, 10000*(1-0)) = 10000；DB lq=10000；sq-lq=0（DB降级路径不可用） |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-EXCP-001 |
|----------|-------------|
| 用例名称 | Step 1 Redis初始化失败-不执行后续步骤 |
| 前置条件 | Redis服务异常（模拟连接超时） |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=10000, idempotentKey="lock-006") |
| 预期结果 | Step 1失败，直接返回错误码REDIS_INIT_FAILED；不执行DB事务（lq不变）；不更新路由缓存 |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | LOCK-EXCP-002 |
|----------|-------------|
| 用例名称 | Step 2 DB事务失败-Lua原子清理Redis分桶 |
| 前置条件 | Redis正常，DB事务执行失败（模拟UPDATE影响0行） |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=10000, idempotentKey="lock-007") |
| 预期结果 | Step 1成功（Redis分桶已初始化）；Step 2失败；Lua原子清理脚本回滚Redis分桶（所有bucket Key + meta + total_remaining被DEL）；DB事务自动回滚（lq不变） |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | LOCK-EXCP-003 |
|----------|-------------|
| 用例名称 | Step 3 路由更新失败-重试3次+后台补偿 |
| 前置条件 | Step 1和Step 2成功，路由更新Lua脚本执行失败 |
| 测试步骤 | 1. 模拟路由更新失败；2. 验证重试3次；3. 等待后台补偿任务执行 |
| 预期结果 | 重试3次后仍失败，lockOrder已创建但路由缓存未更新；后台补偿任务扫描5秒前ACTIVE但无路由的lockOrder，补偿更新路由缓存 |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

| 用例编号 | LOCK-FUNC-008 |
|----------|-------------|
| 用例名称 | 锁库存释放-复用合并提交流程 |
| 前置条件 | lockOrder-A(status=ACTIVE, lockQuantity=10000)，已卖出300件 |
| 测试步骤 | 1. 调用releaseLock(lockOrderId=A) |
| 预期结果 | 触发合并提交流程：sq减少300，wq增加300，lq减少10000；剩余9700件未卖出库存自然保留在sq中；lockOrder-A status=ARCHIVED |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-009 |
|----------|-------------|
| 用例名称 | 锁库存超时自动释放 |
| 前置条件 | lockOrder-A(status=ACTIVE, expireTime已过期) |
| 测试步骤 | 1. LockExpireCleaner定时任务扫描；2. 检测到过期lockOrder |
| 预期结果 | 自动触发合并提交流程释放库存；发出告警通知 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | LOCK-FUNC-010 |
|----------|-------------|
| 用例名称 | reserve-ratio与min-lock-quantity死区验证 |
| 前置条件 | reserveRatio=0.1, min-lock-quantity=100；inventory: sq=5111, lq=5000, sq-lq=111 |
| 测试步骤 | 1. 调用lockInventory(skuId=10001, lockQuantity=1000, idempotentKey="lock-008") |
| 预期结果 | actualLockQuantity = min(1000, 111*0.9) = min(1000, 99.9) = 99.9 → 取整后 < 100 → 返回LOCK_QUANTITY_EXCEEDED；死区范围：100 <= sq-lq < 112时锁库存失败 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

---

## 4 自动锁库存模块测试

| 用例编号 | AUTO-FUNC-001 |
|----------|-------------|
| 用例名称 | 热点品自动触发锁库存 |
| 前置条件 | 商品A被热点识别系统标记；inventory: sq=20000, lq=0；无活跃lockOrder |
| 测试步骤 | 1. 第一笔扣减请求到达 |
| 预期结果 | 自动为商品A创建lockOrder-A(lockQuantity=10000, 16桶)；写入活跃路由缓存；后续扣减请求路由到lockOrder-A |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | AUTO-FUNC-002 |
|----------|-------------|
| 用例名称 | 滚动创建新lockOrder-消除空窗期 |
| 前置条件 | lockOrder-A(total_remaining降至50%以下) |
| 测试步骤 | 1. 自动锁库存模块检测到余量阈值触发；2. 创建lockOrder-B |
| 预期结果 | lockOrder-B的Redis分桶初始化完成、DB lq更新完成、lockOrder记录插入完成后，原子更新路由缓存指向lockOrder-B；lockOrder-A仍可继续扣减 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | AUTO-FUNC-003 |
|----------|-------------|
| 用例名称 | 同步快检触发-total_remaining低于阈值 |
| 前置条件 | lockOrder-A(total_remaining=4500, lockQuantity=10000, triggerRatio=0.5) |
| 测试步骤 | 1. 扣减请求中读取total_remaining；2. 检测到4500 < 5000(阈值) |
| 预期结果 | 异步发送AutoLockEvent(fire-and-forget)；不阻塞扣减请求主路径 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | AUTO-FUNC-004 |
|----------|-------------|
| 用例名称 | 事件去重-SETNX auto_lock_pending |
| 前置条件 | inventory:{skuId}:auto_lock_pending Key不存在 |
| 测试步骤 | 1. 多个扣减请求同时检测到余量低于阈值；2. 竞争SETNX |
| 预期结果 | 仅第一个请求成功SETNX并发送事件；后续请求SETNX失败跳过事件发送；Key TTL=5s自动过期 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | AUTO-FUNC-005 |
|----------|-------------|
| 用例名称 | 后台定时任务兜底-防止事件丢失 |
| 前置条件 | AutoLockEvent丢失（模拟异步事件丢弃） |
| 测试步骤 | 1. 模拟事件丢失；2. 等待后台定时任务执行(500ms间隔) |
| 预期结果 | 定时任务扫描到total_remaining低于阈值，触发自动锁库存 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | AUTO-FUNC-006 |
|----------|-------------|
| 用例名称 | 活跃lockOrder数量控制-max-active限制 |
| 前置条件 | 商品A已有2个ACTIVE lockOrder(max-active=2) |
| 测试步骤 | 1. 自动锁库存模块尝试创建第3个lockOrder |
| 预期结果 | DB事务内SELECT COUNT(*) FOR UPDATE检测到已达上限；事务回滚；Redis分桶清理；不创建新lockOrder |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | AUTO-EXCP-001 |
|----------|-------------|
| 用例名称 | 无可用库存时自动锁库存失败 |
| 前置条件 | inventory: sq=5000, lq=5000, sq-lq=0 |
| 测试步骤 | 1. 自动锁库存模块尝试创建新lockOrder |
| 预期结果 | DB UPDATE影响行数为0；返回LOCK_QUANTITY_EXCEEDED；不创建Redis分桶；后续扣减请求走DB降级路径 |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

| 用例编号 | AUTO-FUNC-007 |
|----------|-------------|
| 用例名称 | 自动锁库存与手动锁库存兼容 |
| 前置条件 | 手动锁库存创建了lockOrder-A |
| 测试步骤 | 1. 自动锁库存模块检测到余量低于阈值；2. 创建lockOrder-B |
| 预期结果 | 手动锁库存创建的lockOrder-A纳入活跃路由管理；新lockOrder-B创建后路由缓存原子切换 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

---

## 5 活跃lockOrder路由模块测试

| 用例编号 | ROUTE-FUNC-001 |
|----------|-------------|
| 用例名称 | 自动路由到活跃lockOrder |
| 前置条件 | inventory:active_lock:10001 = "lockOrder-A"；lockOrder-A分桶索引有效 |
| 测试步骤 | 1. 扣减请求(skuId=10001, lockOrderId未指定) |
| 预期结果 | 查询inventory:active_lock:10001获取lockOrder-A；检查分桶索引有效；走Redis分桶扣减路径 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | ROUTE-FUNC-002 |
|----------|-------------|
| 用例名称 | 活跃lockOrder原子切换 |
| 前置条件 | lockOrder-B创建完成（Redis分桶初始化+DB lq更新+lockOrder记录插入完成） |
| 测试步骤 | 1. 路由更新Lua脚本原子执行：SET active_lock + RPUSH active_lock_history |
| 预期结果 | inventory:active_lock:10001 = "lockOrder-B"；inventory:active_lock_history:10001追加lockOrder-B；新扣减请求自动路由到lockOrder-B |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | ROUTE-EXCP-001 |
|----------|-------------|
| 用例名称 | 路由缓存失效兜底-DB重建路由 |
| 前置条件 | inventory:active_lock:10001不存在（Redis重启后丢失） |
| 测试步骤 | 1. 扣减请求到达；2. 路由缓存未命中 |
| 预期结果 | 查询DB: SELECT id FROM lock_inventory_order WHERE sku_id=10001 AND status='ACTIVE'；找到lockOrder-A；重建路由缓存；继续扣减 |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | ROUTE-EXCP-002 |
|----------|-------------|
| 用例名称 | 路由缓存失效且无ACTIVE lockOrder-降级DB |
| 前置条件 | inventory:active_lock:10001不存在；DB无ACTIVE lockOrder |
| 测试步骤 | 1. 扣减请求到达；2. 路由缓存未命中；3. DB查询无ACTIVE lockOrder |
| 预期结果 | 降级走DB直接扣减路径 |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | ROUTE-FUNC-003 |
|----------|-------------|
| 用例名称 | 活跃路由lockOrder分桶索引失效-历史路由兜底 |
| 前置条件 | inventory:active_lock:10001 = "lockOrder-B"；lockOrder-B的meta已失效（正在合并提交） |
| 测试步骤 | 1. 扣减请求到达；2. 检查lockOrder-B分桶索引已失效；3. 查询active_lock_history |
| 预期结果 | 遍历历史路由列表；找到有效且有余量的lockOrder-A；使用lockOrder-A扣减 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | ROUTE-FUNC-004 |
|----------|-------------|
| 用例名称 | 历史路由遍历约束-max-history-scan |
| 前置条件 | active_lock_history包含5个lockOrder；max-history-scan=3 |
| 测试步骤 | 1. 活跃路由失效；2. 遍历历史路由 |
| 预期结果 | 最多遍历3个lockOrder；余量为0的跳过；找到有效lockOrder则使用；全部无效则降级DB |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | ROUTE-FUNC-005 |
|----------|-------------|
| 用例名称 | 历史路由遍历超时 |
| 前置条件 | history-scan-timeout-ms=5 |
| 测试步骤 | 1. 活跃路由失效；2. 历史路由遍历总耗时超过5ms |
| 预期结果 | 超时后直接降级走DB直接扣减 |
| 优先级 | P2 |
| 测试类型 | 功能测试 |

| 用例编号 | ROUTE-FUNC-006 |
|----------|-------------|
| 用例名称 | 路由缓存主动清理 |
| 前置条件 | lockOrder-A合并提交完成；无新的ACTIVE lockOrder |
| 测试步骤 | 1. 合并提交完成后检查是否有新ACTIVE lockOrder |
| 预期结果 | 主动删除inventory:active_lock:10001路由缓存；减少后续扣减请求的无效Redis GET操作 |
| 优先级 | P2 |
| 测试类型 | 功能测试 |

| 用例编号 | ROUTE-FUNC-007 |
|----------|-------------|
| 用例名称 | 历史列表异步清理 |
| 前置条件 | lockOrder-A合并提交完成(ARCHIVED) |
| 测试步骤 | 1. 合并提交完成后发送异步事件；2. 后台任务清理历史列表 |
| 预期结果 | 从active_lock_history中移除lockOrder-A；清理延迟不影响正确性（遍历时余量预检可跳过） |
| 优先级 | P2 |
| 测试类型 | 功能测试 |

---

## 6 Redis分桶扣减模块测试

| 用例编号 | DEDUCT-FUNC-001 |
|----------|-------------|
| 用例名称 | 正常扣减流程-合并下单明细(Lua返回1) |
| 前置条件 | lockOrder-A(16桶，每桶625)；total_remaining=10000 |
| 测试步骤 | 1. 调用deduct(orderId="ORD-001", skuId=10001, quantity=10) |
| 预期结果 | Lua脚本返回1；DB插入deduction_detail(deduct_path=MERGE_BUCKETS, status=PENDING, lock_order_id=A, bucket_index=随机桶编号)；返回扣减成功 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-FUNC-002 |
|----------|-------------|
| 用例名称 | 分桶耗尽触发合并(Lua返回2) |
| 前置条件 | lockOrder-A: bucket:0=10, total_remaining=10 |
| 测试步骤 | 1. 调用deduct(orderId="ORD-002", skuId=10001, quantity=10) |
| 预期结果 | Lua脚本返回2；DB插入deduction_detail(status=PENDING)；异步触发lockOrder-A的合并提交 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-FUNC-003 |
|----------|-------------|
| 用例名称 | 单桶耗尽fallover |
| 前置条件 | lockOrder-A: bucket:3=0(耗尽)，其他桶有余量 |
| 测试步骤 | 1. 随机选择到bucket:3执行扣减；2. Lua返回0；3. fallover到其他桶 |
| 预期结果 | 自动fallover到其他桶重试(最多3次)；其他桶扣减成功后正常插入DB明细 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-FUNC-004 |
|----------|-------------|
| 用例名称 | 所有桶不足降级DB |
| 前置条件 | lockOrder-A: 所有桶余量均不足(quantity=10) |
| 测试步骤 | 1. fallover 3次均返回0 |
| 预期结果 | 降级走DB直接扣减；INSERT deduction_detail(deduct_path=DIRECT_DB, status=MERGED, lock_order_id=NULL) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-EXCP-001 |
|----------|-------------|
| 用例名称 | Redis异常降级DB |
| 前置条件 | Redis服务超时/不可用 |
| 测试步骤 | 1. 调用deduct；2. Redis操作超时 |
| 预期结果 | 当作失败处理，降级走DB直接扣减；INSERT deduction_detail(deduct_path=DIRECT_DB, status=MERGED) |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | DEDUCT-FUNC-005 |
|----------|-------------|
| 用例名称 | 紧急降级开关检查 |
| 前置条件 | inventory:emergency_degrade:10001 = true |
| 测试步骤 | 1. 调用deduct；2. 检查紧急降级开关 |
| 预期结果 | 跳过Redis路径，直接走DB降级扣减 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-FUNC-006 |
|----------|-------------|
| 用例名称 | 扣减幂等-uk_order_sku唯一索引 |
| 前置条件 | 已存在deduction_detail(orderId="ORD-001", skuId=10001) |
| 测试步骤 | 1. 调用deduct(orderId="ORD-001", skuId=10001, quantity=10) |
| 预期结果 | 幂等检查SELECT发现已存在；直接返回已有明细；Lua未执行，无需INCR回补 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-EXCP-002 |
|----------|-------------|
| 用例名称 | DB明细插入失败-INCR回补Redis |
| 前置条件 | Lua扣减成功(bucket:5, quantity=10)；DB INSERT失败(非唯一索引冲突) |
| 测试步骤 | 1. Lua扣减成功；2. DB INSERT异常 |
| 预期结果 | INCR回补bucket:5和total_remaining各10；返回扣减失败 |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | DEDUCT-FUNC-007 |
|----------|-------------|
| 用例名称 | 唯一索引冲突INCR回补 |
| 前置条件 | Lua扣减成功(bucket:5, quantity=10)；DB INSERT因uk_order_sku冲突失败 |
| 测试步骤 | 1. Lua扣减成功；2. DB INSERT DuplicateKeyException |
| 预期结果 | INCR回补bucket:5和total_remaining各10；返回已有明细ID（幂等视为成功） |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-FUNC-008 |
|----------|-------------|
| 用例名称 | DB降级扣减路径-DIRECT_DB |
| 前置条件 | 无活跃lockOrder；inventory: sq=10000, lq=0 |
| 测试步骤 | 1. 调用deduct(orderId="ORD-003", skuId=10001, quantity=10) |
| 预期结果 | DB事务内：UPDATE inventory SET sq=sq-10, wq=wq+10 WHERE id=10001 AND sq-lq>=10；INSERT deduction_detail(deduct_path=DIRECT_DB, status=MERGED, lock_order_id=NULL) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | DEDUCT-EXCP-003 |
|----------|-------------|
| 用例名称 | DB降级扣减sq-lq不足-返回INSUFFICIENT_STOCK |
| 前置条件 | inventory: sq=5000, lq=5000, sq-lq=0 |
| 测试步骤 | 1. 调用deduct(orderId="ORD-004", skuId=10001, quantity=10) |
| 预期结果 | UPDATE影响行数为0；返回错误码INSUFFICIENT_STOCK |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

---

## 7 合并提交模块测试

| 用例编号 | MERGE-FUNC-001 |
|----------|-------------|
| 用例名称 | 正常合并提交 |
| 前置条件 | lockOrder-A(lockQuantity=1000, status=ACTIVE)；100条PENDING明细(总扣减500件) |
| 测试步骤 | 1. 触发mergeScheduler.triggerMerge(lockOrderId=A) |
| 预期结果 | 获取分布式锁成功；失效分桶索引；分配merge_batch_id；事务内：Step4a标记100条PENDING→MERGED；Step4b计算net_deduction=500；Step4c获取currentLockQuantity=1000；Step4d: sq-=500, wq+=500, lq-=1000；Step4e: lockOrder-A status=ARCHIVED；释放分布式锁；清理Redis分桶；merge_completed=true |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | MERGE-FUNC-002 |
|----------|-------------|
| 用例名称 | 多lockOrder并存时的合并-lq减量更新 |
| 前置条件 | lockOrder-A(lockQuantity=10000)和lockOrder-B(lockQuantity=10000)同时ACTIVE；inventory.lq=20000 |
| 测试步骤 | 1. 触发lockOrder-A合并提交，net_deduction=7000 |
| 预期结果 | inventory: sq=sq-7000, wq=wq+7000, lq=lq-10000=10000；lockOrder-B仍然ACTIVE；lq=10000正确反映lockOrder-B的锁定量 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | MERGE-FUNC-003 |
|----------|-------------|
| 用例名称 | 合并提交重复触发-幂等保障 |
| 前置条件 | lockOrder-A已执行过一次合并提交(status=ARCHIVED) |
| 测试步骤 | 1. 再次触发mergeScheduler.triggerMerge(lockOrderId=A) |
| 预期结果 | 分布式锁保证同一时刻只有一个合并任务执行；WHERE status='PENDING' AND merge_batch_id IS NULL过滤已合并记录；Step 4a影响0行，直接跳过；不会重复执行lq减量更新 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | MERGE-EXCP-001 |
|----------|-------------|
| 用例名称 | 合并提交DB更新部分失败-事务回滚 |
| 前置条件 | lockOrder-A有PENDING明细；模拟Step 4d UPDATE影响0行 |
| 测试步骤 | 1. 触发合并提交；2. Step 4d执行失败 |
| 预期结果 | 整个事务回滚；sq/wq/lq不变；明细仍为PENDING；下次合并任务重试时重新处理 |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | MERGE-EXCP-002 |
|----------|-------------|
| 用例名称 | 合并提交sq不足-WHERE约束触发 |
| 前置条件 | net_deduction=500，但当前sq=300 |
| 测试步骤 | 1. 触发合并提交；2. Step 4d: WHERE sq >= 500 |
| 预期结果 | UPDATE影响行数为0；事务回滚；触发告警；进入人工处理流程 |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

| 用例编号 | MERGE-FUNC-004 |
|----------|-------------|
| 用例名称 | 扣减屏障-失效分桶索引 |
| 前置条件 | lockOrder-A正在合并提交 |
| 测试步骤 | 1. 合并提交Step 2: DEL inventory:lock:A:meta；2. 新扣减请求到达 |
| 预期结果 | 新请求检查分桶索引已失效；尝试历史路由兜底；全部无效则降级DB；屏障为性能优化，穿透后事务内先标记后计算保证正确性 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | MERGE-FUNC-005 |
|----------|-------------|
| 用例名称 | 先标记后计算-竞态处理 |
| 前置条件 | 合并提交进行中；PENDING取消与合并提交并发 |
| 测试步骤 | 1. 合并提交Step 4a: UPDATE获取行锁标记PENDING→MERGED；2. CANCEL操作被阻塞 |
| 预期结果 | 合并提交事务提交后CANCEL获取行锁；检查状态=MERGED；走MERGED取消路径(wq回补sq) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | MERGE-FUNC-006 |
|----------|-------------|
| 用例名称 | COALESCE防止NULL |
| 前置条件 | Step 4a影响0行（无PENDING明细） |
| 测试步骤 | 1. Step 4b: SELECT COALESCE(SUM(quantity), 0) |
| 预期结果 | SUM返回NULL时COALESCE转为0；net_deduction=0；跳过Step 4d |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | MERGE-FUNC-007 |
|----------|-------------|
| 用例名称 | 分布式锁提前释放 |
| 前置条件 | 合并提交Step 4事务已完成 |
| 测试步骤 | 1. Step 4.5释放分布式锁；2. Step 5清理Redis分桶；3. Step 6更新merge_completed |
| 预期结果 | Step 5-6是幂等操作（重复DEL/UPDATE不影响正确性）；提前释放锁减少锁持有时间 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | MERGE-FUNC-008 |
|----------|-------------|
| 用例名称 | 锁库存主动释放-复用合并提交 |
| 前置条件 | lockOrder-A(lockQuantity=1000, status=ACTIVE)，已卖出300件 |
| 测试步骤 | 1. 调用releaseLock(lockOrderId=A) |
| 预期结果 | 触发合并提交流程：sq减少300，wq增加300，lq减少1000；剩余700件未卖出库存自然保留在sq中 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

---

## 8 补偿合并模块测试

| 用例编号 | COMP-FUNC-001 |
|----------|-------------|
| 用例名称 | 孤立PENDING明细补偿 |
| 前置条件 | deduction_detail(status=PENDING, lock_order_id=A)；lockOrder-A(status=ARCHIVED) |
| 测试步骤 | 1. CompensateTask定时扫描；2. 发现孤立PENDING明细 |
| 预期结果 | 获取分布式锁(compensate:A)；事务内先标记后计算：UPDATE status=MERGED, merge_batch_id=COMP-{uuid}；SELECT SUM计算net_deduction；UPDATE inventory sq-=net_deduction, wq+=net_deduction WHERE sq>=net_deduction；释放分布式锁 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | COMP-FUNC-002 |
|----------|-------------|
| 用例名称 | 补偿合并分布式锁-互斥 |
| 前置条件 | 补偿任务正在处理lockOrder-A |
| 测试步骤 | 1. 另一个补偿任务尝试处理同一lockOrder-A |
| 预期结果 | tryLock(0, 30, SECONDS)获取失败；直接跳过，不重复处理 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | COMP-EXCP-001 |
|----------|-------------|
| 用例名称 | 补偿合并sq不足-WHERE约束触发告警 |
| 前置条件 | 孤立PENDING明细net_deduction=500；当前sq=300 |
| 测试步骤 | 1. 补偿合并执行；2. UPDATE inventory WHERE sq >= 500 |
| 预期结果 | UPDATE影响行数为0；事务回滚；触发告警 |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

| 用例编号 | COMP-FUNC-003 |
|----------|-------------|
| 用例名称 | 崩溃恢复-启动时扫描merge_completed=false |
| 前置条件 | lockOrder-A(status=ARCHIVED, merge_completed=false)；Redis分桶残留 |
| 测试步骤 | 1. 应用重启；2. 启动时扫描未完成记录 |
| 预期结果 | 补偿清理Redis分桶(bucket keys + meta + total_remaining)；更新merge_completed=true |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | COMP-FUNC-004 |
|----------|-------------|
| 用例名称 | 路由缓存补偿修复 |
| 前置条件 | lockOrder-A(status=ACTIVE, created_at < NOW()-5s)；路由缓存中无lockOrder-A |
| 测试步骤 | 1. 后台补偿任务扫描5秒前ACTIVE但无路由的lockOrder |
| 预期结果 | 补偿更新路由缓存inventory:active_lock:10001 = lockOrder-A |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

---

## 9 回补管理模块测试

| 用例编号 | REFUND-FUNC-001 |
|----------|-------------|
| 用例名称 | PENDING取消-条件INCR回补Lua脚本 |
| 前置条件 | deduction_detail(status=PENDING, lock_order_id=A, bucket_index=5, quantity=10)；lockOrder-A的meta Key有效 |
| 测试步骤 | 1. 调用cancel(orderId="ORD-001", skuId=10001) |
| 预期结果 | deduction_detail status更新为CANCELLED；Lua脚本原子执行：检查meta有效→INCR回补bucket:5和total_remaining各10；DB库存无需操作(sq/wq/lq不变) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-002 |
|----------|-------------|
| 用例名称 | PENDING取消时meta已失效-跳过INCR |
| 前置条件 | deduction_detail(status=PENDING, lock_order_id=A, bucket_index=5, quantity=10)；lockOrder-A的meta Key已被DEL(合并提交中) |
| 测试步骤 | 1. 调用cancel(orderId="ORD-002", skuId=10001) |
| 预期结果 | deduction_detail status更新为CANCELLED；Lua脚本返回0(meta已失效)；跳过INCR回补；DB库存无需操作 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-003 |
|----------|-------------|
| 用例名称 | MERGED取消-wq回补sq+refund_detail |
| 前置条件 | deduction_detail(status=MERGED, quantity=10)；inventory: wq=100 |
| 测试步骤 | 1. 调用cancel(orderId="ORD-003", skuId=10001) |
| 预期结果 | INSERT refund_detail(refund_quantity=10, status=MERGED)；deduction_detail status更新为CANCELLED；UPDATE inventory SET wq=wq-10, sq=sq+10 WHERE wq>=10 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-EXCP-001 |
|----------|-------------|
| 用例名称 | MERGED取消wq不足-告警 |
| 前置条件 | deduction_detail(status=MERGED, quantity=10)；inventory: wq=5 |
| 测试步骤 | 1. 调用cancel(orderId="ORD-004", skuId=10001) |
| 预期结果 | UPDATE影响行数为0(wq<10)；触发告警；进入人工处理流程 |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

| 用例编号 | REFUND-FUNC-004 |
|----------|-------------|
| 用例名称 | OCCUPIED退款-oq回补sq+refund_detail |
| 前置条件 | deduction_detail(status=OCCUPIED, quantity=10)；inventory: oq=100 |
| 测试步骤 | 1. 调用refund(orderId="ORD-005", skuId=10001, refundQuantity=10, refundId="REF-001") |
| 预期结果 | INSERT refund_detail(refund_quantity=10, status=MERGED)；deduction_detail status更新为REFUNDED；UPDATE inventory SET oq=oq-10, sq=sq+10 WHERE oq>=10 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-005 |
|----------|-------------|
| 用例名称 | 部分退款 |
| 前置条件 | deduction_detail(status=OCCUPIED, quantity=10)；inventory: oq=100 |
| 测试步骤 | 1. 调用refund(orderId="ORD-006", skuId=10001, refundQuantity=3, refundId="REF-002") |
| 预期结果 | INSERT refund_detail(refund_quantity=3)；UPDATE inventory SET oq=oq-3, sq=sq+3 WHERE oq>=3；deduction_detail status仍为OCCUPIED（非REFUNDED，因为未全额退款） |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-006 |
|----------|-------------|
| 用例名称 | 退款业务幂等-refund_request_id |
| 前置条件 | 已存在refund_detail(ref_detail_id=detailId, refund_request_id="REQ-001") |
| 测试步骤 | 1. 调用refund(orderId="ORD-007", skuId=10001, refundQuantity=10, refundId="REF-003", refundRequestId="REQ-001") |
| 预期结果 | INSERT因uk_ref_detail_request唯一索引冲突失败；视为幂等命中，返回成功 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-007 |
|----------|-------------|
| 用例名称 | 付款确认-MERGED→OCCUPIED |
| 前置条件 | deduction_detail(status=MERGED, quantity=10)；inventory: wq=100 |
| 测试步骤 | 1. 调用confirm(orderId="ORD-008", skuId=10001) |
| 预期结果 | deduction_detail status更新为OCCUPIED；UPDATE inventory SET wq=wq-10, oq=oq+10 WHERE wq>=10 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-EXCP-002 |
|----------|-------------|
| 用例名称 | 付款确认wq不足 |
| 前置条件 | deduction_detail(status=MERGED, quantity=10)；inventory: wq=5 |
| 测试步骤 | 1. 调用confirm(orderId="ORD-009", skuId=10001) |
| 预期结果 | UPDATE影响行数为0(wq<10)；触发告警 |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

| 用例编号 | REFUND-FUNC-008 |
|----------|-------------|
| 用例名称 | PENDING取消与合并提交竞态-时序1(CANCEL在合并前完成) |
| 前置条件 | deduction_detail(status=PENDING) |
| 测试步骤 | 1. CANCEL操作先执行：检查status=PENDING→更新为CANCELLED→条件INCR回补Redis；2. 合并提交后执行：WHERE status='PENDING'不包含已CANCELLED明细 |
| 预期结果 | CANCEL成功；合并提交净扣减值不包含已取消明细；数据一致 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-009 |
|----------|-------------|
| 用例名称 | PENDING取消与合并提交竞态-时序2(CANCEL在合并后执行) |
| 前置条件 | deduction_detail(status=PENDING) |
| 测试步骤 | 1. 合并提交先执行：UPDATE获取行锁→标记PENDING为MERGED→事务提交；2. CANCEL后执行：检查status=MERGED→走MERGED取消路径 |
| 预期结果 | CANCEL走MERGED取消路径(wq回补sq)；数据一致 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-010 |
|----------|-------------|
| 用例名称 | PENDING取消与合并提交竞态-时序3(并发) |
| 前置条件 | deduction_detail(status=PENDING) |
| 测试步骤 | 1. 合并提交Step 4a: UPDATE获取行锁；2. CANCEL被阻塞；3. 合并提交事务提交；4. CANCEL获取行锁 |
| 预期结果 | CANCEL检查状态=MERGED→走MERGED取消路径(wq回补sq)；数据一致 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-011 |
|----------|-------------|
| 用例名称 | 取消幂等-已CANCELLED直接返回成功 |
| 前置条件 | deduction_detail(status=CANCELLED) |
| 测试步骤 | 1. 调用cancel(orderId, skuId) |
| 预期结果 | 检查status已为CANCELLED；直接返回成功；不重复执行回补操作 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | REFUND-FUNC-012 |
|----------|-------------|
| 用例名称 | 付款确认幂等-已OCCUPIED直接返回成功 |
| 前置条件 | deduction_detail(status=OCCUPIED) |
| 测试步骤 | 1. 调用confirm(orderId, skuId) |
| 预期结果 | 检查status已为OCCUPIED；直接返回成功 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

---

## 10 紧急降级模块测试

| 用例编号 | EMER-FUNC-001 |
|----------|-------------|
| 用例名称 | 紧急解锁接口-逐个合并提交 |
| 前置条件 | lockOrder-A和lockOrder-B均ACTIVE；inventory.lq=20000 |
| 测试步骤 | 1. 调用emergencyUnlock(skuId=10001, force=false) |
| 预期结果 | 对lockOrder-A和lockOrder-B逐个触发紧急合并提交(按lockOrderId维度加分布式锁串行处理)；lq逐步释放；DB降级路径可用 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | EMER-FUNC-002 |
|----------|-------------|
| 用例名称 | 强制解锁-SET lq=0+同步ARCHIVED lockOrder |
| 前置条件 | Redis不可用；sq-lq=0；合并提交耗时过长 |
| 测试步骤 | 1. 调用emergencyUnlock(skuId=10001, force=true) |
| 预期结果 | 先使用Lua脚本批量清零所有ACTIVE lockOrder的Redis分桶；SET lq=0；同步UPDATE lock_inventory_order SET status='ARCHIVED' WHERE sku_id=10001 AND status='ACTIVE'；设置emergency_degrade开关(TTL=30s) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | EMER-FUNC-003 |
|----------|-------------|
| 用例名称 | emergency_degrade开关-跳过Redis路径 |
| 前置条件 | inventory:emergency_degrade:10001 = true |
| 测试步骤 | 1. 扣减请求到达；2. 检查紧急降级开关 |
| 预期结果 | 跳过Redis路径，直接走DB降级扣减；开关TTL=30s自动过期 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | EMER-FUNC-004 |
|----------|-------------|
| 用例名称 | Redis不可用自动检测-连续超时次数 |
| 前置条件 | Redis连续超时次数 < failThreshold(5次) |
| 测试步骤 | 1. 模拟Redis连续超时5次 |
| 预期结果 | failCount >= failThreshold；自动触发紧急合并提交；释放所有ACTIVE lockOrder的lq |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | EMER-FUNC-005 |
|----------|-------------|
| 用例名称 | 预留DB降级额度验证 |
| 前置条件 | reserveRatio=0.1；inventory: sq=10000, lq=9000, sq-lq=1000 |
| 测试步骤 | 1. Redis不可用时走DB降级扣减 |
| 预期结果 | DB降级路径可用额度=sq-lq=1000；WHERE sq-lq >= quantity约束保护Redis预锁库存不被侵占 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | EMER-EXCP-001 |
|----------|-------------|
| 用例名称 | 禁止直接SET lq=0-防止超卖 |
| 前置条件 | lockOrder-A(ACTIVE)；Redis部分恢复 |
| 测试步骤 | 1. 不通过紧急解锁接口，直接UPDATE inventory SET lq=0 |
| 预期结果 | 系统设计禁止此操作；直接清零lq会移除DB降级路径对Redis预锁库存的保护屏障；若Redis路径和DB降级路径同时扣减同一批库存，导致超卖 |
| 优先级 | P0 |
| 测试类型 | 异常测试 |

---

## 11 并发场景测试

| 用例编号 | CONC-CONC-001 |
|----------|-------------|
| 用例名称 | 同一SKU并发锁库存-SQL行锁防护 |
| 前置条件 | inventory: sq=10000, lq=0 |
| 测试步骤 | 1. 100个线程并发调用lockInventory(skuId=10001, lockQuantity=1000) |
| 预期结果 | InnoDB行锁保证串行执行；lq最终值 <= sq；WHERE sq-lq >= lockQuantity确保不超锁；所有成功请求的lockQuantity之和 <= sq |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-002 |
|----------|-------------|
| 用例名称 | 同一SKU并发扣减-Redis Lua原子操作 |
| 前置条件 | lockOrder-A(16桶，每桶625，total_remaining=10000) |
| 测试步骤 | 1. 1000个线程并发调用deduct(skuId=10001, quantity=1) |
| 预期结果 | Lua原子操作保证不超卖；成功扣减总数 <= 10000；total_remaining最终 >= 0 |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-003 |
|----------|-------------|
| 用例名称 | 同一SKU并发DB降级扣减-SQL行锁防护 |
| 前置条件 | inventory: sq=10000, lq=0 |
| 测试步骤 | 1. 100个线程并发走DB降级扣减(quantity=100) |
| 预期结果 | InnoDB行锁保证串行执行；WHERE sq-lq >= quantity确保不超卖；成功扣减总数 <= 10000 |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-004 |
|----------|-------------|
| 用例名称 | 同一lockOrder并发合并提交-分布式锁互斥 |
| 前置条件 | lockOrder-A(ACTIVE) |
| 测试步骤 | 1. 2个线程并发触发lockOrder-A的合并提交 |
| 预期结果 | 分布式锁(merge:A)保证同一时刻只有一个合并任务执行；第二个线程tryLock(0,...)获取失败直接返回 |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-005 |
|----------|-------------|
| 用例名称 | PENDING取消与合并提交竞态 |
| 前置条件 | deduction_detail(status=PENDING) |
| 测试步骤 | 1. 线程1执行CANCEL；线程2执行合并提交；并发执行 |
| 预期结果 | 行锁保证竞态安全：CANCEL在合并前完成→PENDING取消路径+INCR回补；CANCEL在合并后执行→MERGED取消路径+wq回补sq |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-006 |
|----------|-------------|
| 用例名称 | 同一订单重复扣减-唯一索引 |
| 前置条件 | 无 |
| 测试步骤 | 1. 2个线程并发调用deduct(orderId="ORD-001", skuId=10001, quantity=10) |
| 预期结果 | uk_order_sku唯一索引保证只有一条扣减明细；第二个线程INSERT冲突后INCR回补Redis+返回成功(幂等) |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-007 |
|----------|-------------|
| 用例名称 | 同一幂等键重复锁库存-唯一索引 |
| 前置条件 | 无 |
| 测试步骤 | 1. 2个线程并发调用lockInventory(idempotentKey="lock-001") |
| 预期结果 | uk_idempotent_key唯一索引保证只有一个lockOrder创建；第二个线程INSERT冲突后使用预生成lockOrderId执行Lua清理脚本回滚Redis分桶 |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-008 |
|----------|-------------|
| 用例名称 | 多lockOrder并存合并-lq减量更新+非负约束 |
| 前置条件 | lockOrder-A(lockQuantity=10000)和lockOrder-B(lockQuantity=10000)同时ACTIVE；inventory.lq=20000 |
| 测试步骤 | 1. 并发触发lockOrder-A和lockOrder-B的合并提交 |
| 预期结果 | 各自独立合并；lq减量更新：lq=lq-10000(各自减自己的lockQuantity)；WHERE lq>=currentLockQuantity防止lq变负 |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

| 用例编号 | CONC-CONC-009 |
|----------|-------------|
| 用例名称 | 二次合并触发-Step 4a影响0行跳过 |
| 前置条件 | lockOrder-A已合并完成(ARCHIVED) |
| 测试步骤 | 1. 再次触发lockOrder-A的合并提交 |
| 预期结果 | Step 4a: WHERE status='PENDING' AND merge_batch_id IS NULL → 影响0行；直接跳过Step 4b-4e；避免lq变负 |
| 优先级 | P0 |
| 测试类型 | 并发测试 |

---

## 12 数据一致性验证测试

| 用例编号 | CONSIS-CONS-001 |
|----------|-------------|
| 用例名称 | Redis层防超卖-Lua原子检查+扣减 |
| 前置条件 | bucket:0 = 5 |
| 测试步骤 | 1. 100个线程并发扣减quantity=1(同一桶) |
| 预期结果 | 最多5个线程成功；Lua原子操作保证桶计数器不变负 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-002 |
|----------|-------------|
| 用例名称 | DB层防超卖-WHERE sq-lq >= quantity |
| 前置条件 | inventory: sq=100, lq=80, sq-lq=20 |
| 测试步骤 | 1. 并发DB降级扣减quantity=15 |
| 预期结果 | 最多1个线程成功(15 <= 20)；其余线程UPDATE影响0行返回INSUFFICIENT_STOCK |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-003 |
|----------|-------------|
| 用例名称 | 合并提交最终防线-WHERE sq>=net_deduction AND lq>=currentLockQuantity |
| 前置条件 | inventory: sq=100, lq=1000 |
| 测试步骤 | 1. 合并提交net_deduction=200 |
| 预期结果 | WHERE sq >= 200条件不满足(sq=100)；UPDATE影响0行；事务回滚；sq不变负 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-004 |
|----------|-------------|
| 用例名称 | 四字段非负约束-CHECK约束验证 |
| 前置条件 | inventory表含CHECK约束(sq>=0 AND wq>=0 AND oq>=0 AND lq>=0) |
| 测试步骤 | 1. 尝试UPDATE使sq变为负值 |
| 预期结果 | CHECK约束阻止更新（MySQL 8.x默认强制执行CHECK约束）；或SQL层WHERE约束先拦截 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-005 |
|----------|-------------|
| 用例名称 | 扣减明细唯一索引防重复 |
| 前置条件 | deduction_detail(orderId="ORD-001", skuId=10001)已存在 |
| 测试步骤 | 1. 再次INSERT相同(orderId, skuId) |
| 预期结果 | uk_order_sku唯一索引冲突；INSERT失败；不产生重复扣减 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-006 |
|----------|-------------|
| 用例名称 | PENDING取消防少卖-条件INCR回补 |
| 前置条件 | lockOrder-A(bucket:5=50, total_remaining=500)；deduction_detail(PENDING, bucket_index=5, quantity=10) |
| 测试步骤 | 1. 取消PENDING明细 |
| 预期结果 | 条件INCR回补bucket:5和total_remaining各10；分桶余量恢复；后续扣减可使用恢复的余量；不少卖 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-007 |
|----------|-------------|
| 用例名称 | DB明细插入失败防少卖-INCR回补 |
| 前置条件 | Lua扣减成功(bucket:5 -= 10)；DB INSERT失败 |
| 测试步骤 | 1. 捕获DB INSERT异常；2. INCR回补Redis |
| 预期结果 | INCR回补bucket:5和total_remaining各10；分桶余量恢复；不少卖 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-008 |
|----------|-------------|
| 用例名称 | 合并提交竞态防少卖-先标记后计算 |
| 前置条件 | 合并提交进行中；新PENDING明细在Step 4a后插入 |
| 测试步骤 | 1. Step 4a标记现有PENDING→MERGED；2. 新PENDING明细在Step 4a后插入 |
| 预期结果 | 新PENDING明细未被本次合并包含；下次合并或补偿任务处理；净扣减值与实际MERGED明细一致；不少卖 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-009 |
|----------|-------------|
| 用例名称 | 孤立PENDING明细防少卖-补偿合并 |
| 前置条件 | deduction_detail(PENDING, lock_order_id=A)；lockOrder-A(ARCHIVED) |
| 测试步骤 | 1. 补偿任务扫描发现孤立PENDING明细 |
| 预期结果 | 补偿合并处理孤立明细；sq减少对应数量；不少卖 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

| 用例编号 | CONSIS-CONS-010 |
|----------|-------------|
| 用例名称 | lq与lock_inventory_order一致性 |
| 前置条件 | lockOrder-A(lockQuantity=10000, ACTIVE)；lockOrder-B(lockQuantity=5000, ACTIVE) |
| 测试步骤 | 1. 验证inventory.lq = SUM(lock_quantity WHERE status='ACTIVE') |
| 预期结果 | inventory.lq = 15000；与两个ACTIVE lockOrder的lockQuantity之和一致 |
| 优先级 | P0 |
| 测试类型 | 一致性测试 |

---

## 13 Redis Cluster兼容性测试

| 用例编号 | CLUSTER-FUNC-001 |
|----------|-------------|
| 用例名称 | Hash Tag Key格式验证 |
| 前置条件 | Redis Cluster模式部署 |
| 测试步骤 | 1. 验证所有Key使用Hash Tag格式：inventory:{lockOrderId}:lock:bucket:{n} |
| 预期结果 | Key格式正确；Hash Tag为{lockOrderId}或{skuId} |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | CLUSTER-FUNC-002 |
|----------|-------------|
| 用例名称 | 同一lockOrder的Key在同一slot |
| 前置条件 | Redis Cluster模式部署 |
| 测试步骤 | 1. 创建lockOrder-A；2. 检查所有相关Key的slot分布 |
| 预期结果 | inventory:{lockOrderId}:lock:bucket:0..15、meta、total_remaining均在同一slot；Lua脚本可正常执行(跨Key操作要求同slot) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | CLUSTER-FUNC-003 |
|----------|-------------|
| 用例名称 | 同一skuId的路由Key在同一slot |
| 前置条件 | Redis Cluster模式部署 |
| 测试步骤 | 1. 检查路由相关Key的slot分布 |
| 预期结果 | inventory:{skuId}:active_lock、active_lock_history、auto_lock_pending、emergency_degrade均在同一slot |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | CLUSTER-FUNC-004 |
|----------|-------------|
| 用例名称 | Lua脚本跨slot执行验证 |
| 前置条件 | Redis Cluster模式部署 |
| 测试步骤 | 1. 执行deduct.lua(KEYS含bucket和total_remaining) |
| 预期结果 | 因Hash Tag确保同一lockOrderId的Key在同一slot；Lua脚本正常执行无CROSSSLOT错误 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

---

## 14 可观测性测试

| 用例编号 | METRIC-FUNC-001 |
|----------|-------------|
| 用例名称 | 扣减指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置 |
| 测试步骤 | 1. 执行Redis扣减成功；2. 触发fallover；3. 降级DB扣减 |
| 预期结果 | store.deduct.redis.success.count递增；store.deduct.redis.fallover.count递增；store.deduct.redis.degrade.count递增；store.deduct.redis.degrade.ratio正确计算 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | METRIC-FUNC-002 |
|----------|-------------|
| 用例名称 | 合并指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置 |
| 测试步骤 | 1. 触发合并提交 |
| 预期结果 | store.merge.delay.ms记录合并延迟；store.merge.batch.size记录合并明细数量 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | METRIC-FUNC-003 |
|----------|-------------|
| 用例名称 | 锁库存指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置 |
| 测试步骤 | 1. 执行锁库存；2. 触发锁超时释放 |
| 预期结果 | store.lock.utilization正确计算(实际扣减量/lq锁定量)；store.lock.expire.count递增 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | METRIC-FUNC-004 |
|----------|-------------|
| 用例名称 | 补偿指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置 |
| 测试步骤 | 1. 触发补偿合并；2. 模拟补偿失败 |
| 预期结果 | store.compensate.merge.count递增；store.compensate.merge.fail.count递增(失败时) |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | METRIC-FUNC-005 |
|----------|-------------|
| 用例名称 | 自动锁库存指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置 |
| 测试步骤 | 1. 自动锁库存创建成功；2. 自动锁库存创建失败 |
| 预期结果 | store.auto-lock.create.count递增；store.auto-lock.fail.count递增(失败时) |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

| 用例编号 | METRIC-FUNC-006 |
|----------|-------------|
| 用例名称 | 路由指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置 |
| 测试步骤 | 1. 路由缓存命中；2. 路由缓存未命中(查DB) |
| 预期结果 | store.active-lock.route.hit.count递增(命中)；store.active-lock.route.miss.count递增(未命中) |
| 优先级 | P2 |
| 测试类型 | 功能测试 |

| 用例编号 | METRIC-FUNC-007 |
|----------|-------------|
| 用例名称 | 紧急降级指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置 |
| 测试步骤 | 1. 触发紧急解锁；2. emergency_degrade开关生效 |
| 预期结果 | store.emergency.unlock.count递增；store.emergency.degrade.count递增 |
| 优先级 | P2 |
| 测试类型 | 功能测试 |

| 用例编号 | METRIC-FUNC-008 |
|----------|-------------|
| 用例名称 | 崩溃恢复指标采集验证 |
| 前置条件 | Micrometer + Prometheus已配置；存在merge_completed=false的ARCHIVED记录 |
| 测试步骤 | 1. 应用重启；2. 崩溃恢复执行 |
| 预期结果 | store.merge.crash.recover.count递增 |
| 优先级 | P2 |
| 测试类型 | 功能测试 |

---

## 15 API接口测试

### 15.1 锁库存接口

| 用例编号 | API-FUNC-001 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/lock - 正常锁库存 |
| 前置条件 | inventory: sq=20000, lq=0 |
| 测试步骤 | 1. POST /api/v1/inventory/lock {"skuId":10001,"lockQuantity":10000,"idempotentKey":"lock-001"} |
| 预期结果 | HTTP 200；code=0；data.lockOrderId非空；data.actualLockQuantity=9000(10000*(1-0.1))；data.bucketCount=16；data.expireTime非空 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | API-FUNC-002 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/lock - 可用额度不足 |
| 前置条件 | inventory: sq=50, lq=0 |
| 测试步骤 | 1. POST /api/v1/inventory/lock {"skuId":10001,"lockQuantity":10000,"idempotentKey":"lock-002"} |
| 预期结果 | HTTP 409；code=102001；message含LOCK_QUANTITY_EXCEEDED |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | API-FUNC-003 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/lock - 幂等命中 |
| 前置条件 | 已存在lockOrder(idempotentKey="lock-001") |
| 测试步骤 | 1. POST /api/v1/inventory/lock {"skuId":10001,"lockQuantity":10000,"idempotentKey":"lock-001"} |
| 预期结果 | HTTP 200；code=0；返回已有lockOrderId |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.2 释放锁库存接口

| 用例编号 | API-FUNC-004 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/lock/{lockOrderId}/release - 正常释放 |
| 前置条件 | lockOrder-A(ACTIVE, lockQuantity=1000)，已卖出300件 |
| 测试步骤 | 1. POST /api/v1/inventory/lock/A/release |
| 预期结果 | HTTP 200；code=0；data.netDeduction=300；data.releasedQuantity=700；data.status=ARCHIVED |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.3 扣减库存接口

| 用例编号 | API-FUNC-005 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/deduct - Redis分桶扣减成功 |
| 前置条件 | lockOrder-A(ACTIVE, 16桶有余量) |
| 测试步骤 | 1. POST /api/v1/inventory/deduct {"orderId":"ORD-001","skuId":10001,"quantity":10} |
| 预期结果 | HTTP 200；code=0；data.deductPath=MERGE_BUCKETS；data.status=PENDING；data.lockOrderId非空；data.bucketIndex非空 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | API-FUNC-006 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/deduct - DB降级扣减成功 |
| 前置条件 | 无活跃lockOrder；inventory: sq=10000, lq=0 |
| 测试步骤 | 1. POST /api/v1/inventory/deduct {"orderId":"ORD-002","skuId":10001,"quantity":10} |
| 预期结果 | HTTP 200；code=0；data.deductPath=DIRECT_DB；data.status=MERGED；data.lockOrderId=null；data.bucketIndex=null |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | API-FUNC-007 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/deduct - 库存不足 |
| 前置条件 | inventory: sq=0, lq=0 |
| 测试步骤 | 1. POST /api/v1/inventory/deduct {"orderId":"ORD-003","skuId":10001,"quantity":10} |
| 预期结果 | HTTP 409；code=101001；message含INSUFFICIENT_STOCK |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.4 查询库存接口

| 用例编号 | API-FUNC-008 |
|----------|-------------|
| 用例名称 | GET /api/v1/inventory/{skuId} - 查询库存信息 |
| 前置条件 | inventory: sq=8500, wq=1000, oq=500, lq=2000 |
| 测试步骤 | 1. GET /api/v1/inventory/10001 |
| 预期结果 | HTTP 200；code=0；data.sq=8500；data.wq=1000；data.oq=500；data.lq=2000；data.availableQuantity=6500(sq-lq) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.5 手动触发合并接口

| 用例编号 | API-FUNC-009 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/merge - 手动触发合并 |
| 前置条件 | lockOrder-A(ACTIVE, 有PENDING明细) |
| 测试步骤 | 1. POST /api/v1/inventory/merge {"lockOrderId":"A"} |
| 预期结果 | HTTP 200；code=0；data.mergeBatchId非空；data.mergedCount>0；data.netDeduction>0；data.status=ARCHIVED |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

### 15.6 付款确认接口

| 用例编号 | API-FUNC-010 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/confirm - 付款确认 |
| 前置条件 | deduction_detail(status=MERGED) |
| 测试步骤 | 1. POST /api/v1/inventory/confirm {"orderId":"ORD-001","skuId":10001} |
| 预期结果 | HTTP 200；code=0；data.previousStatus=MERGED；data.currentStatus=OCCUPIED |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.7 取消订单接口

| 用例编号 | API-FUNC-011 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/cancel - PENDING取消 |
| 前置条件 | deduction_detail(status=PENDING) |
| 测试步骤 | 1. POST /api/v1/inventory/cancel {"orderId":"ORD-001","skuId":10001} |
| 预期结果 | HTTP 200；code=0；data.previousStatus=PENDING；data.currentStatus=CANCELLED；data.refundDetailId=null(PENDING取消不创建refund_detail) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | API-FUNC-012 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/cancel - MERGED取消 |
| 前置条件 | deduction_detail(status=MERGED) |
| 测试步骤 | 1. POST /api/v1/inventory/cancel {"orderId":"ORD-002","skuId":10001} |
| 预期结果 | HTTP 200；code=0；data.previousStatus=MERGED；data.currentStatus=CANCELLED；data.refundDetailId非空；data.refundQuantity>0 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.8 退款接口

| 用例编号 | API-FUNC-013 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/refund - 全额退款 |
| 前置条件 | deduction_detail(status=OCCUPIED, quantity=10) |
| 测试步骤 | 1. POST /api/v1/inventory/refund {"orderId":"ORD-003","skuId":10001,"refundQuantity":10,"refundId":"REF-001","refundRequestId":"REQ-001"} |
| 预期结果 | HTTP 200；code=0；data.previousStatus=OCCUPIED；data.currentStatus=REFUNDED |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.9 紧急解锁接口

| 用例编号 | API-FUNC-014 |
|----------|-------------|
| 用例名称 | POST /api/v1/inventory/emergency-unlock - 正常解锁 |
| 前置条件 | lockOrder-A和lockOrder-B均ACTIVE；inventory.lq=20000 |
| 测试步骤 | 1. POST /api/v1/inventory/emergency-unlock {"skuId":10001,"force":false} |
| 预期结果 | HTTP 200；code=0；data.releasedLockOrders包含A和B；data.previousLq=20000；data.currentLq=0 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

### 15.10 查询扣减明细接口

| 用例编号 | API-FUNC-015 |
|----------|-------------|
| 用例名称 | GET /api/v1/inventory/deduction/{orderId} - 查询扣减明细 |
| 前置条件 | deduction_detail(orderId="ORD-001")已存在 |
| 测试步骤 | 1. GET /api/v1/inventory/deduction/ORD-001 |
| 预期结果 | HTTP 200；code=0；data.orderId=ORD-001；data.status非空；data.quantity>0 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

### 15.11 查询锁库存单据接口

| 用例编号 | API-FUNC-016 |
|----------|-------------|
| 用例名称 | GET /api/v1/inventory/lock-order/{lockOrderId} - 查询锁库存单据 |
| 前置条件 | lockOrder-A已存在 |
| 测试步骤 | 1. GET /api/v1/inventory/lock-order/A |
| 预期结果 | HTTP 200；code=0；data.lockOrderId=A；data.status=ACTIVE/ARCHIVED；data.statistics非空 |
| 优先级 | P1 |
| 测试类型 | 功能测试 |

### 15.12 API参数校验

| 用例编号 | API-EXCP-001 |
|----------|-------------|
| 用例名称 | 参数校验-必填字段缺失 |
| 前置条件 | 无 |
| 测试步骤 | 1. POST /api/v1/inventory/deduct {"orderId":null,"skuId":10001,"quantity":10} |
| 预期结果 | HTTP 400；code=199001；message含PARAM_INVALID |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

| 用例编号 | API-EXCP-002 |
|----------|-------------|
| 用例名称 | 参数校验-quantity<=0 |
| 前置条件 | 无 |
| 测试步骤 | 1. POST /api/v1/inventory/deduct {"orderId":"ORD","skuId":10001,"quantity":0} |
| 预期结果 | HTTP 400；code=199001；message含PARAM_INVALID |
| 优先级 | P1 |
| 测试类型 | 异常测试 |

---

## 16 端到端场景测试

| 用例编号 | E2E-FUNC-001 |
|----------|-------------|
| 用例名称 | 完整链路：锁库存→扣减→合并→付款确认→退款 |
| 前置条件 | inventory: sq=20000, wq=0, oq=0, lq=0 |
| 测试步骤 | 1. 锁库存(lockQuantity=10000)；2. 扣减(orderId=ORD-001, quantity=10)；3. 等待合并提交；4. 付款确认；5. 退款(refundQuantity=10) |
| 预期结果 | 锁库存后：lq=9000, sq=20000；扣减后：Redis分桶减少10，deduction_detail(PENDING)；合并后：sq=19990, wq=10, lq=0, deduction_detail(MERGED)；付款确认后：wq=0, oq=10, deduction_detail(OCCUPIED)；退款后：oq=0, sq=20000, deduction_detail(REFUNDED) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | E2E-FUNC-002 |
|----------|-------------|
| 用例名称 | 完整链路：锁库存→扣减→合并→取消 |
| 前置条件 | inventory: sq=20000, wq=0, oq=0, lq=0 |
| 测试步骤 | 1. 锁库存(lockQuantity=10000)；2. 扣减(orderId=ORD-002, quantity=10)；3. 等待合并提交；4. 取消订单 |
| 预期结果 | 合并后：sq=19990, wq=10, lq=0；取消后：wq=0, sq=20000, deduction_detail(CANCELLED), refund_detail已创建 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | E2E-FUNC-003 |
|----------|-------------|
| 用例名称 | 完整链路：自动锁库存→扣减→合并→滚动管线 |
| 前置条件 | inventory: sq=20000, wq=0, oq=0, lq=0 |
| 测试步骤 | 1. 自动锁库存创建lockOrder-A；2. 扣减至total_remaining低于阈值；3. 自动锁库存创建lockOrder-B；4. lockOrder-A合并提交；5. 扣减路由到lockOrder-B |
| 预期结果 | 滚动管线无空窗期；lockOrder-A合并期间扣减请求路由到lockOrder-B；sq/wq/lq各阶段值正确 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | E2E-FUNC-004 |
|----------|-------------|
| 用例名称 | 完整链路：Redis降级→DB扣减→付款确认→退款 |
| 前置条件 | inventory: sq=10000, wq=0, oq=0, lq=0；Redis不可用 |
| 测试步骤 | 1. 扣减请求降级走DB；2. 付款确认；3. 退款 |
| 预期结果 | DB扣减：sq=9990, wq=10, deduction_detail(DIRECT_DB, MERGED)；付款确认：wq=0, oq=10, deduction_detail(OCCUPIED)；退款：oq=0, sq=10000, deduction_detail(REFUNDED) |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

| 用例编号 | E2E-FUNC-005 |
|----------|-------------|
| 用例名称 | 完整链路：紧急降级→恢复→重新锁库存 |
| 前置条件 | inventory: sq=10000, lq=10000(全锁定)；Redis不可用 |
| 测试步骤 | 1. 紧急解锁释放lq；2. DB降级扣减；3. Redis恢复；4. 重新锁库存；5. Redis路径扣减 |
| 预期结果 | 紧急解锁后lq=0；DB降级扣减成功；Redis恢复后重新锁库存；后续扣减走Redis路径 |
| 优先级 | P0 |
| 测试类型 | 功能测试 |

---

## 附录A：测试用例统计

| 模块 | P0 | P1 | P2 | 合计 |
|------|-----|-----|-----|------|
| Lua脚本 | 6 | 3 | 1 | 10 |
| 锁库存管理 | 8 | 3 | 0 | 11 |
| 自动锁库存 | 3 | 4 | 0 | 7 |
| 活跃lockOrder路由 | 4 | 2 | 2 | 8 |
| Redis分桶扣减 | 8 | 1 | 0 | 9 |
| 合并提交 | 5 | 3 | 0 | 8 |
| 补偿合并 | 2 | 2 | 0 | 4 |
| 回补管理 | 8 | 3 | 0 | 11 |
| 紧急降级 | 4 | 1 | 0 | 5 |
| 并发场景 | 9 | 0 | 0 | 9 |
| 数据一致性 | 10 | 0 | 0 | 10 |
| Redis Cluster | 3 | 1 | 0 | 4 |
| 可观测性 | 0 | 4 | 4 | 8 |
| API接口 | 11 | 5 | 0 | 16 |
| 端到端场景 | 5 | 0 | 0 | 5 |
| **合计** | **86** | **32** | **7** | **125** |

## 附录B：配置参数参考

| 配置项 | 默认值 | 测试中建议值 |
|--------|--------|-------------|
| store.bucket.count | 16 | 4(降低测试复杂度) |
| store.merge.delay-ms | 1000 | 500(加速测试) |
| store.auto-lock.quantity | 10000 | 1000 |
| store.auto-lock.trigger-ratio | 0.5 | 0.5 |
| store.auto-lock.max-active | 2 | 2 |
| store.auto-lock.min-lock-quantity | 100 | 10(降低测试门槛) |
| store.auto-lock.reserve-ratio | 0.1 | 0.1 |
| store.auto-lock.check-interval-ms | 500 | 200 |
| store.routing.max-history-scan | 3 | 3 |
| store.routing.history-scan-timeout-ms | 5 | 5 |
| store.redis.fail-threshold | 5 | 3(加速触发) |
