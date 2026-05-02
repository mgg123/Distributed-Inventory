# 基于Redis分布式强一致库存扣减系统 — 系统概要设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 系统名称 | 基于Redis分布式强一致库存扣减系统 |
| 文档版本 | V2.0 |
| 编写日期 | 2026-05-02 |
| 文档状态 | 更新（同步spec.md第六轮评审修复） |

## 1 引言

### 1.1 编写目的

本文档为"基于Redis分布式强一致库存扣减系统"的系统概要设计文档，旨在从全局视角描述系统的整体架构、模块划分、核心设计决策和技术选型，为后续详细设计、编码实现和测试验证提供顶层指导。

### 1.2 读者对象

- 系统架构师
- 后端开发工程师
- 测试工程师
- 运维工程师
- 技术管理者

### 1.3 术语与缩略语

| 术语/缩略语 | 全称 | 说明 |
|-------------|------|------|
| sq | Saleable Quantity | 可售库存，用户可见的可购买数量 |
| wq | Withheld Quantity | 预扣库存，下单后从sq转移到wq |
| oq | Occupied Quantity | 占用库存，付款后从wq转移到oq |
| lq | Locked Quantity | 预锁库存，提前锁定到Redis的数量 |
| lockOrder | Lock Inventory Order | 锁库存单据，记录一次锁库存操作 |
| TPS | Transactions Per Second | 每秒事务处理量 |
| Lua | Lua Script | Redis内嵌脚本语言，保证操作原子性 |
| DB降级 | Database Degradation | Redis不可用时回退到数据库直接扣减 |
| Hash Tag | Redis Cluster Hash Tag | `{...}`语法确保Key在同一hash slot |

### 1.4 参考资料

- 《基于Redis分布式强一致库存扣减系统 Spec》（V6.0，经六轮评审修复）
- 《系统设计评审报告 Round5》
- 《逻辑Bug纠察报告 Round6》

## 2 系统概述

### 2.1 系统背景

针对热点深库存下单抢购场景（如直播带货、秒杀活动），传统数据库直接扣减方式性能瓶颈明显。在高并发场景下，数据库行锁竞争激烈，单SKU扣减TPS通常不超过1000，无法满足1W+ TPS的业务需求。

本系统通过引入分布式缓存（Redis）作为库存扣减的预扣减层，将高并发扣减请求分散到Redis分桶中执行，大幅提升扣减TPS，同时通过严格的合并提交和一致性保障机制，保证**强一致性**：绝对不允许超卖或少卖。

### 2.2 系统目标

| 目标维度 | 指标 |
|----------|------|
| 扣减TPS | 相比纯DB提升5-10倍，单SKU支持10000+ TPS |
| 一致性延迟 | ≤ 2秒（合并窗口期） |
| 数据一致性 | 强一致，绝对不超卖不少卖 |
| 可用性 | Redis故障时自动降级至DB模式；核心链路不中断 |
| 可观测性 | 核心业务指标全覆盖，支持Prometheus监控 |
| Redis Cluster兼容 | 所有Key使用Hash Tag，兼容Cluster模式 |

### 2.3 系统边界

本系统为库存服务的核心子系统，上游对接交易/订单系统，下游依赖MySQL和Redis基础设施。

**系统职责范围内：**
- 库存扣减（Redis预扣减 + DB降级扣减）
- 锁库存管理（手动/自动锁库存）
- 合并提交（异步批量将Redis扣减结果同步到DB）
- 扣减明细生命周期管理（PENDING → MERGED → OCCUPIED → CANCELLED/REFUNDED）
- 回补管理（取消/退款时的库存恢复）
- 一致性保障（补偿机制、崩溃恢复、紧急降级）

**系统职责范围外：**
- 订单创建与支付流程
- 热点识别算法（由外部系统提供标记）
- Redis/MySQL基础设施运维

## 3 系统架构设计

### 3.1 整体架构

系统采用DDD+COLA分层架构，自上而下分为适配层、应用层、领域层和基础设施层。

```
┌─────────────────────────────────────────────────────────────┐
│                      调用方（交易/订单）                        │
└──────────────────────────┬──────────────────────────────────┘
                           │ REST API
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                     适配层 (Adapter)                          │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  InventoryWriteController / InventoryReadController    │  │
│  │  (CQRS分离: 写CmdExe + 读QueryExe)                     │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    应用层 (App)                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ LockAppSvc  │  │DeductAppSvc │  │  MergeAppSvc        │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────────┴──────────┐  │
│  │AutoLockAppSvc│ │RefundAppSvc │  │ EmergencyAppSvc     │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 定时任务: MergeScheduler / Compensate / Expire / AutoLock│   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    领域层 (Domain) — 核心无外部依赖              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ Inventory    │  │ Deduction    │  │ Refund           │  │
│  │ Aggregate    │  │ Aggregate    │  │ Context          │  │
│  │ +LockOrder   │  │ +Detail      │  │ +RefundDetail    │  │
│  │ +DomainSvc   │  │ +DomainSvc   │  │ +DomainSvc       │  │
│  └──────────────┘  └──────────────┘  └──────────────────┘  │
│  ┌──────────────┐  ┌──────────────────────────────────────┐ │
│  │ Routing      │  │ Gateway接口(仅定义)                    │ │
│  │ DomainSvc    │  │ RedisBucket/Router/DistLock           │ │
│  └──────────────┘  └──────────────────────────────────────┘ │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    基础设施层 (Infrastructure)                  │
│  ┌─────────────────┐  ┌─────────────────────────────────┐   │
│  │  Redis访问层     │  │        DB访问层                  │   │
│  │  ┌───────────┐  │  │  ┌──────────┐ ┌──────────────┐  │   │
│  │  │ Lua脚本   │  │  │  │Inventory │ │LockOrder     │  │   │
│  │  │ 执行器    │  │  │  │Mapper    │ │Mapper        │  │   │
│  │  ├───────────┤  │  │  ├──────────┤ ├──────────────┤  │   │
│  │  │ 分桶网关   │  │  │  │Deduction │ │Refund        │  │   │
│  │  │ 实现      │  │  │  │Mapper    │ │Mapper        │  │   │
│  │  ├───────────┤  │  │  └──────────┘ └──────────────┘  │   │
│  │  │ 路由网关   │  │  │                                 │   │
│  │  │ 实现      │  │  │                                 │   │
│  │  ├───────────┤  │  │                                 │   │
│  │  │ 分布式锁   │  │  │                                 │   │
│  │  │ 实现      │  │  │                                 │   │
│  │  └───────────┘  │  │                                 │   │
│  └─────────────────┘  └─────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                      基础设施层                               │
│  ┌─────────────────┐  ┌─────────────────────────────────┐   │
│  │   Redis Cluster │  │        MySQL (InnoDB)           │   │
│  │  ┌───────────┐  │  │  ┌──────────────────────────┐  │   │
│  │  │ per-      │  │  │  │ inventory (sq/wq/oq/lq)  │  │   │
│  │  │ lockOrder │  │  │  ├──────────────────────────┤  │   │
│  │  │ buckets   │  │  │  │ lock_inventory_order     │  │   │
│  │  │ total_    │  │  │  ├──────────────────────────┤  │   │
│  │  │ remaining │  │  │  │ deduction_detail         │  │   │
│  │  │ 分桶索引   │  │  │  ├──────────────────────────┤  │   │
│  │  │ 路由缓存   │  │  │  │ refund_detail            │  │   │
│  │  │ 降级开关   │  │  │  └──────────────────────────┘  │   │
│  │  └───────────┘  │  │                                 │   │
│  └─────────────────┘  └─────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 架构风格与设计原则

| 原则 | 说明 |
|------|------|
| Redis仅作计数器 | Redis分桶仅作为防止超卖的手段，不是真实库存存储 |
| DB明细是真相源 | 最终扣减成功与否以DB明细存在为准 |
| 先标记后计算 | 合并提交事务内先UPDATE标记状态再SELECT SUM计算，消除竞态 |
| 扣减屏障为性能优化 | 合并提交时先失效分桶索引缓存减少穿透，非正确性必要条件 |
| lq减量更新 | 合并提交时lq减去当前lockOrder的lockQuantity，支持多lockOrder并存 |
| 降级优先保可用 | Redis异常时自动降级DB路径，保证业务可用性 |
| 补偿机制完善 | 针对各环节失败场景提供完整补偿（崩溃恢复、孤立明细补偿、对账） |
| 四字段非负约束 | sq/wq/oq/lq均有SQL层WHERE非负防线，防止任何字段变负 |
| Redis Cluster兼容 | 所有Key使用Hash Tag `{实体ID}` 确保同一实体的Key在同一slot |
| 紧急降级开关 | emergency_degrade开关确保降级期间Redis路径暂停，防止数据不一致 |

### 3.3 技术选型

| 技术领域 | 选型 | 选型理由 |
|----------|------|----------|
| 应用框架 | Spring Boot 4.0.6 | 项目已采用，生态成熟 |
| ORM框架 | MyBatis-Plus | 简化实体映射，提供通用CRUD，支持复杂SQL |
| Redis客户端 | Redisson | 支持分布式锁(RLock)、Lua脚本(RScript)、原子操作(RAtomicLong) |
| 数据库 | MySQL 8.x (InnoDB) | 支持事务、行锁，成熟稳定 |
| 定时调度 | Spring @Scheduled | 轻量级，满足合并提交和补偿扫描需求 |
| 监控 | Micrometer + Prometheus | Spring Boot原生集成，指标采集标准化 |
| 分布式锁 | Redisson RLock | 基于Redis实现，支持看门狗自动续期 |
| 架构风格 | DDD + COLA | 领域驱动设计，分层隔离，扩展点机制 |

## 4 模块划分

### 4.1 模块总览

| 模块名称 | 职责 | 核心类 |
|----------|------|--------|
| 锁库存管理模块 | 将DB行库存从sq预锁定到lq，初始化Redis分桶 | InventoryLockAppService |
| 自动锁库存模块 | 热点识别驱动自动锁库存，滚动创建lockOrder | AutoLockAppService |
| 活跃lockOrder路由模块 | 扣减请求自动路由到当前可用lockOrder | RoutingDomainService |
| Redis分桶扣减模块 | 基于Redis分桶的高并发库存预扣减 | InventoryDeductAppService |
| 合并提交模块 | 异步批量将Redis扣减结果汇总到DB | InventoryMergeAppService |
| 补偿合并模块 | 处理孤立PENDING明细和崩溃恢复 | CompensateTask |
| 紧急降级模块 | Redis不可用时的紧急解锁和降级处理 | EmergencyAppService |
| 锁超时释放模块 | 扫描过期锁库存单据自动释放 | LockExpireTask |
| 回补管理模块 | 取消/退款时的库存恢复和明细状态流转 | InventoryRefundAppService |
| 可观测性模块 | 核心业务指标监控 | MetricsCollector |

### 4.2 模块依赖关系（DDD分层）

```
Adapter层
  InventoryWriteController / InventoryReadController
    ├── InventoryLockCmdExe → InventoryLockAppService
    ├── InventoryDeductCmdExe → InventoryDeductAppService
    ├── InventoryRefundCmdExe → InventoryRefundAppService
    ├── InventoryMergeCmdExe → InventoryMergeAppService
    └── EmergencyUnlockCmdExe → EmergencyAppService

App层
  InventoryLockAppService → InventoryDomainService + RedisBucketGateway + ActiveLockRouterGateway
  InventoryDeductAppService → DeductionDomainService + RedisBucketGateway + ActiveLockRouterGateway
  InventoryMergeAppService → DeductionDomainService + InventoryDomainService
  AutoLockAppService → InventoryLockAppService + ActiveLockRouterGateway
  EmergencyAppService → InventoryMergeAppService + RedisBucketGateway

Domain层（无外部依赖）
  InventoryDomainService → InventoryAggregate + LockInventoryOrder
  DeductionDomainService → DeductionDetailAggregate
  RefundDomainService → RefundDetail
  RoutingDomainService → ActiveLockRoute

Infrastructure层（实现Domain接口）
  RedisBucketGatewayImpl → Redisson RScript (Lua脚本)
  ActiveLockRouterGatewayImpl → RedisTemplate (路由缓存)
  DistributedLockGatewayImpl → Redisson RLock
  InventoryRepositoryImpl → InventoryMapper
  LockOrderRepositoryImpl → LockOrderMapper
  DeductionDetailRepositoryImpl → DeductionDetailMapper
  RefundDetailRepositoryImpl → RefundDetailMapper
```

## 5 核心流程概要

### 5.1 库存扣减核心链路

系统核心链路分为7个阶段：

```
[自动锁库存] → [锁库存预热] → [下单扣减] → [合并提交] → [付款确认] → [取消/退款] → [补偿/恢复]
```

**阶段1 - 自动锁库存**：热点识别触发自动锁库存，创建lockOrder并初始化Redis分桶，建立活跃路由。事件去重（SETNX）防止重复触发。

**阶段2 - 锁库存预热**：将DB行库存从sq预锁定到lq字段，同步初始化Redis分桶计数器，创建锁库存单据作为父单据。严格时序：Redis→DB事务→路由更新。Step 3失败有重试+后台补偿。

**阶段3 - 下单扣减**：扣减请求通过活跃路由找到lockOrder，在Redis分桶中执行Lua原子扣减（含total_remaining检查），插入PENDING状态扣减明细；Lua返回2时分桶耗尽异步触发合并；Redis异常时降级走DB直接扣减。扣减前检查紧急降级开关。

**阶段4 - 合并提交**：定时任务按lockOrder维度聚合PENDING明细，在事务内"先标记后计算"得到净扣减值，原子更新DB库存字段（sq减少、wq增加、lq减量更新），清除Redis分桶。Step 4a影响0行时跳过（幂等保障）。分布式锁提前释放。

**阶段5 - 付款确认**：明细状态从MERGED流转为OCCUPIED，DB库存字段wq减少、oq增加。

**阶段6 - 取消/退款**：根据明细当前状态执行对应回补操作（PENDING→条件INCR回补Redis、MERGED→wq回补sq、OCCUPIED→oq回补sq），插入回补明细。refund_detail有业务级幂等约束（uk_ref_detail_request）。

**阶段7 - 补偿/恢复**：处理孤立PENDING明细补偿合并、应用崩溃后Redis分桶清理、锁库存超时自动释放。路由缓存丢失有后台补偿修复。

### 5.2 数据流向

```
Redis分桶计数器 ←──Lua扣减──→ 扣减请求
       │
       │ (合并提交)
       ▼
DB inventory表 (sq/wq/oq/lq) ←──DB降级扣减──→ 扣减请求
       │
       │ (明细驱动)
       ▼
DB deduction_detail / refund_detail
```

## 6 一致性保障体系概要

### 6.1 防超卖机制

| 层级 | 机制 | 说明 |
|------|------|------|
| Redis层 | Lua脚本原子检查+扣减（含total_remaining防御性检查） | 防止DECR后计数器变负 |
| DB层 | WHERE sq - lq >= quantity | DB降级路径防超卖 |
| DB层 | WHERE sq >= #{net_deduction} AND lq >= #{currentLockQuantity} | 合并提交最终防线，sq/lq双非负约束 |
| DB层 | WHERE wq >= #{quantity} / WHERE oq >= #{quantity} | 取消/退款非负约束 |
| 应用层 | 扣减明细唯一索引 | (order_id, sku_id) 防重复扣减 |

### 6.2 防少卖机制

| 场景 | 机制 | 说明 |
|------|------|------|
| PENDING取消 | 条件INCR回补Redis分桶（Lua原子：检查meta+INCR） | 恢复分桶余量，防止少卖 |
| DB明细插入失败 | INCR回补Redis分桶 | 根据bucket_index精确回补 |
| 合并提交竞态 | 先标记后计算 | 消除扫描-更新时间窗口 |
| 孤立PENDING明细 | 补偿合并扫描 | 定时补偿处理 |
| 锁库存Step 3失败 | 重试+后台补偿+崩溃恢复 | 路由缓存修复 |

### 6.3 崩溃恢复机制

| 场景 | 恢复策略 |
|------|----------|
| 合并提交后Redis分桶未清理 | 启动时扫描merge_completed=false的ARCHIVED记录，补偿清理 |
| DB锁库存事务失败 | Lua脚本原子清理Redis分桶 |
| 锁库存超时未释放 | 定时扫描过期lockOrder，自动触发合并提交 |
| 锁库存Step 3路由更新失败 | 重试3次+后台补偿扫描+启动时修复 |

## 7 Redis Key设计规范

### 7.1 Key格式映射表（Redis Cluster兼容）

所有Redis Key使用Hash Tag语法 `{...}` 确保同一实体的相关Key分布在同一hash slot：

| 逻辑Key | 实际Key格式 | Hash Tag | 说明 |
|---------|------------|----------|------|
| `inventory:lock:{lockOrderId}:bucket:{n}` | `inventory:{lockOrderId}:lock:bucket:{n}` | `{lockOrderId}` | 同一lockOrder的所有桶在同一slot |
| `inventory:lock:{lockOrderId}:meta` | `inventory:{lockOrderId}:lock:meta` | `{lockOrderId}` | 与桶Key同slot |
| `inventory:lock:{lockOrderId}:total_remaining` | `inventory:{lockOrderId}:lock:total_remaining` | `{lockOrderId}` | 与桶Key同slot |
| `inventory:lock:{lockOrderId}:deduct_qps:{window}` | `inventory:{lockOrderId}:lock:deduct_qps:{window}` | `{lockOrderId}` | 与桶Key同slot |
| `inventory:active_lock:{skuId}` | `inventory:{skuId}:active_lock` | `{skuId}` | 路由Key |
| `inventory:active_lock_history:{skuId}` | `inventory:{skuId}:active_lock_history` | `{skuId}` | 与路由Key同slot |
| `inventory:auto_lock_pending:{skuId}` | `inventory:{skuId}:auto_lock_pending` | `{skuId}` | 与路由Key同slot |
| `inventory:emergency_degrade:{skuId}` | `inventory:{skuId}:emergency_degrade` | `{skuId}` | 与路由Key同slot |

> 文档中为可读性使用逻辑Key格式，实际实现必须使用Hash Tag格式。

## 8 部署架构概要

### 8.1 部署拓扑

```
┌──────────────┐     ┌──────────────┐
│  Nginx/LB    │────→│  Nginx/LB    │
└──────┬───────┘     └──────┬───────┘
       │                    │
       ▼                    ▼
┌──────────────┐     ┌──────────────┐
│ Store Service│     │ Store Service│
│  Instance 1  │     │  Instance 2  │
└──────┬───────┘     └──────┬───────┘
       │                    │
       └────────┬───────────┘
                │
       ┌────────┴────────┐
       │                  │
       ▼                  ▼
┌──────────────┐   ┌──────────────┐
│ Redis Cluster│   │ MySQL Master │
│  (3主3从)    │   │   + Slave    │
└──────────────┘   └──────────────┘
```

### 8.2 关键部署参数

| 参数 | 建议值 | 说明 |
|------|--------|------|
| Store Service实例数 | ≥ 2 | 高可用，支持水平扩展 |
| Redis集群规模 | 3主3从 | 保证Redis高可用，Key使用Hash Tag |
| MySQL部署模式 | 1主1从 | 读写分离可选 |
| 合并提交线程池大小 | 4-8 | 根据lockOrder并发数调整 |

## 9 性能预期

| 指标 | 预期值 | 说明 |
|------|--------|------|
| 单SKU扣减TPS | 10000+ | 16桶 × ~625 TPS/桶 |
| 一致性延迟 | ≤ 2秒 | 合并窗口期 |
| Redis扣减延迟 | < 1ms | 单次Lua脚本执行 |
| DB降级扣减延迟 | < 10ms | 单次事务执行 |
| 合并提交延迟 | < 500ms | 单次批量合并耗时 |
| 降级DB扣减比例 | < 5% | 正常情况下 |

## 10 风险与约束

| 风险/约束 | 影响 | 缓解措施 |
|-----------|------|----------|
| Redis全锁定+Redis不可用 | 系统完全不可用 | 预留DB降级额度(reserve-ratio) + 紧急解锁接口 + emergency_degrade开关 |
| 合并提交空窗期 | 短暂DB压力增大 | 自动锁库存滚动管线消除空窗期 |
| 分桶数运行时不可调 | 灵活性受限 | 锁库存时确定，通过配置调整 |
| 大规模Key场景 | Redis内存压力 | 合并提交后及时清理分桶Key |
| 多lockOrder并存时lq管理复杂 | 维护成本 | lq减量更新 + 严格时序控制 + lq非负约束 |
| reserve-ratio与min-lock-quantity死区 | 约11件可用额度浪费 | 可调低min-lock-quantity或reserve-ratio |
| refund_request_id为NULL时无业务幂等 | 重复退款风险 | 建议调用方始终传入refund_request_id |
