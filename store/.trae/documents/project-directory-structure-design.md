# 项目目录层级结构设计

> 版本 V2.0 — 同步spec.md第六轮评审修复（2026-05-02）

## 目标

基于四份设计文档（概要设计、详细设计、数据库设计、接口设计）和 DDD+COLA 架构规范，为 `store` 模块设计完整的目录层级结构。仅设计结构，不进行编码操作。

## 设计依据

### 1. DDD+COLA 分层架构

采用单模块包结构（项目为单个 `store` 模块），四层架构：

```
Adapter (适配层) → App (应用层) → Domain (领域层) ← Infrastructure (基础设施层)
```

### 2. 限界上下文划分

根据详细设计文档的10大模块，划分为以下限界上下文：

| 限界上下文 | 包路径 | 核心聚合 | 对应模块 |
|-----------|--------|---------|---------|
| Inventory（库存上下文） | domain.inventory | InventoryAggregate | 锁库存、自动锁库存、紧急降级 |
| Deduction（扣减上下文） | domain.deduction | DeductionDetailAggregate | 分桶扣减、合并提交、补偿合并 |
| Routing（路由上下文） | domain.routing | - (值对象+领域服务) | 活跃lockOrder路由 |
| Refund（回补上下文） | domain.refund | RefundDetailAggregate | 回补管理 |

### 3. 类与接口映射

根据详细设计文档的类设计和接口设计文档的API定义，映射到各层：

**Adapter 层** (11个外部API + CQRS分离):
- InventoryWriteController → 锁库存/释放/扣减/取消/退款/确认/合并/紧急解锁
- InventoryReadController → 查询库存/查询明细/查询锁库存单据
- Command/Query 执行器

**App 层** (10大业务模块的应用服务编排):
- InventoryLockAppService → 锁库存/释放
- InventoryDeductAppService → 扣减
- InventoryRefundAppService → 取消/退款/付款确认
- InventoryMergeAppService → 合并提交
- AutoLockAppService → 自动锁库存
- EmergencyAppService → 紧急降级
- 定时任务: MergeSchedulerTask, CompensateTask, LockExpireTask, AutoLockCheckTask

**Domain 层** (核心领域模型):
- 聚合: InventoryAggregate, DeductionDetailAggregate
- 实体: LockInventoryOrder, DeductionDetail, RefundDetail
- 值对象: Quantity, SkuId, OrderId, LockOrderId, BucketMeta, DeductPath, etc.
- 领域服务: InventoryDomainService, DeductionDomainService
- 仓储接口: InventoryRepository, LockOrderRepository, DeductionDetailRepository, RefundDetailRepository
- 网关接口: RedisBucketGateway, ActiveLockRouterGateway, DistributedLockGateway
- 领域事件: AutoLockEvent, InventoryDeductedEvent, MergeCommittedEvent

**Infrastructure 层** (技术实现):
- 仓储实现: InventoryRepositoryImpl, LockOrderRepositoryImpl, etc.
- 网关实现: RedisBucketGatewayImpl, ActiveLockRouterGatewayImpl, DistributedLockGatewayImpl
- 数据对象: InventoryPO, LockInventoryOrderPO, DeductionDetailPO, RefundDetailPO
- Mapper: InventoryMapper, LockOrderMapper, DeductionDetailMapper, RefundDetailMapper
- 转换器: InventoryConverter, LockOrderConverter, DeductionDetailConverter, RefundDetailConverter
- Lua脚本: deduct.lua, init_buckets.lua, cleanup_buckets.lua, incr_refund.lua
- 配置: RedisConfig, RedissonConfig, MybatisPlusConfig, ThreadPoolConfig, StoreProperties

## 目录结构设计

```
com.mgg.exp.store
├── adapter                                      # 适配层
│   ├── controller                               # REST Controller
│   │   ├── InventoryWriteController.java        # 写操作(锁/扣/取消/退款/确认/合并/紧急)
│   │   └── InventoryReadController.java         # 读操作(查询库存/明细/单据)
│   ├── command                                  # Command 执行器(写)
│   │   ├── InventoryLockCmdExe.java             # 锁库存命令执行
│   │   ├── InventoryDeductCmdExe.java           # 扣减命令执行
│   │   ├── InventoryRefundCmdExe.java           # 回补命令执行(取消/退款/付款确认)
│   │   ├── InventoryMergeCmdExe.java            # 合并提交命令执行
│   │   └── EmergencyUnlockCmdExe.java           # 紧急解锁命令执行
│   └── query                                    # Query 执行器(读)
│       ├── InventoryQueryExe.java               # 库存查询
│       ├── DeductionDetailQueryExe.java         # 扣减明细查询
│       └── LockOrderQueryExe.java               # 锁库存单据查询
│
├── app                                          # 应用层
│   ├── service                                  # 应用服务(接口+实现)
│   │   ├── InventoryLockAppService.java
│   │   ├── InventoryDeductAppService.java
│   │   ├── InventoryRefundAppService.java
│   │   ├── InventoryMergeAppService.java
│   │   ├── AutoLockAppService.java
│   │   ├── EmergencyAppService.java
│   │   └── impl
│   │       ├── InventoryLockAppServiceImpl.java
│   │       ├── InventoryDeductAppServiceImpl.java
│   │       ├── InventoryRefundAppServiceImpl.java
│   │       ├── InventoryMergeAppServiceImpl.java
│   │       ├── AutoLockAppServiceImpl.java
│   │       └── EmergencyAppServiceImpl.java
│   ├── assembler                                # 对象转换器(DTO ↔ Domain)
│   │   └── InventoryAssembler.java
│   └── task                                     # 定时任务
│       ├── MergeSchedulerTask.java              # 合并提交调度
│       ├── CompensateTask.java                  # 补偿扫描(孤立明细+崩溃恢复)
│       ├── LockExpireTask.java                  # 锁超时释放
│       └── AutoLockCheckTask.java               # 自动锁库存检测
│
├── domain                                       # 领域层(核心,无外部依赖)
│   ├── inventory                                # 库存限界上下文
│   │   ├── aggregate                            # 聚合
│   │   │   └── InventoryAggregate.java          # 库存聚合根(sq/wq/oq/lq+行为)
│   │   ├── entity                               # 实体
│   │   │   └── LockInventoryOrder.java          # 锁库存单据实体
│   │   ├── valueobject                          # 值对象
│   │   │   ├── Quantity.java                    # 数量(不可变,含校验)
│   │   │   ├── SkuId.java                       # SKU标识
│   │   │   ├── LockOrderId.java                 # 锁库存单据ID
│   │   │   ├── BucketMeta.java                  # 分桶元数据
│   │   │   ├── LockOrderStatus.java             # 锁库存单据状态(ACTIVE/ARCHIVED)
│   │   │   └── LockResult.java                  # 锁库存操作结果
│   │   ├── service                              # 领域服务
│   │   │   └── InventoryDomainService.java      # 库存领域服务(锁库存/释放)
│   │   ├── repository                           # 仓储接口(仅定义)
│   │   │   ├── InventoryRepository.java         # 库存仓储
│   │   │   └── LockOrderRepository.java         # 锁库存单据仓储
│   │   ├── gateway                              # 网关接口(仅定义)
│   │   │   ├── RedisBucketGateway.java          # Redis分桶网关
│   │   │   ├── ActiveLockRouterGateway.java     # 活跃路由网关
│   │   │   ├── DistributedLockGateway.java      # 分布式锁网关
│   │   │   └── EmergencyDegradeGateway.java     # 紧急降级开关网关
│   │   └── event                                # 领域事件
│   │       └── AutoLockEvent.java               # 自动锁库存事件
│   │
│   ├── deduction                                # 扣减限界上下文
│   │   ├── aggregate                            # 聚合
│   │   │   └── DeductionDetailAggregate.java    # 扣减明细聚合根(含状态机)
│   │   ├── entity                               # 实体
│   │   │   └── DeductionDetail.java             # 扣减明细实体
│   │   ├── valueobject                          # 值对象
│   │   │   ├── OrderId.java                     # 订单ID
│   │   │   ├── DetailId.java                    # 明细ID
│   │   │   ├── DeductPath.java                  # 扣减路径(MERGE_BUCKETS/DIRECT_DB)
│   │   │   ├── DeductionStatus.java             # 扣减明细状态(PENDING/MERGED/OCCUPIED/CANCELLED/REFUNDED)
│   │   │   ├── BucketIndex.java                 # 桶编号
│   │   │   ├── MergeBatchId.java                # 合并批次ID
│   │   │   ├── DeductResult.java                # 扣减操作结果
│   │   │   └── MergeResult.java                 # 合并操作结果
│   │   ├── service                              # 领域服务
│   │   │   └── DeductionDomainService.java      # 扣减领域服务(扣减/降级/回补)
│   │   ├── repository                           # 仓储接口(仅定义)
│   │   │   └── DeductionDetailRepository.java   # 扣减明细仓储
│   │   └── event                                # 领域事件
│   │       ├── InventoryDeductedEvent.java      # 库存扣减事件
│   │       └── MergeCommittedEvent.java         # 合并提交事件
│   │
│   ├── refund                                   # 回补限界上下文
│   │   ├── entity                               # 实体
│   │   │   └── RefundDetail.java                # 回补明细实体
│   │   ├── valueobject                          # 值对象
│   │   │   ├── RefundId.java                    # 回补明细ID
│   │   │   ├── RefundRequestId.java            # 退款请求标识(业务级幂等键)
│   │   │   └── RefundQuantity.java              # 回补数量
│   │   ├── service                              # 领域服务
│   │   │   └── RefundDomainService.java         # 回补领域服务(取消/退款)
│   │   └── repository                           # 仓储接口(仅定义)
│   │       └── RefundDetailRepository.java      # 回补明细仓储
│   │
│   └── routing                                  # 路由限界上下文
│       ├── valueobject                          # 值对象
│       │   ├── ActiveLockRoute.java             # 活跃路由信息
│       │   └── RouteResolveResult.java          # 路由解析结果
│       └── service                              # 领域服务
│           └── RoutingDomainService.java        # 路由领域服务(路由解析/兜底)
│
├── infrastructure                               # 基础设施层
│   ├── repository                               # 仓储实现
│   │   ├── InventoryRepositoryImpl.java
│   │   ├── LockOrderRepositoryImpl.java
│   │   ├── DeductionDetailRepositoryImpl.java
│   │   └── RefundDetailRepositoryImpl.java
│   ├── gateway                                  # 网关实现
│   │   ├── RedisBucketGatewayImpl.java          # Redis分桶网关实现(Lua脚本执行)
│   │   ├── ActiveLockRouterGatewayImpl.java     # 活跃路由网关实现(Redis路由缓存)
│   │   ├── DistributedLockGatewayImpl.java      # 分布式锁网关实现(Redisson RLock)
│   │   └── EmergencyDegradeGatewayImpl.java     # 紧急降级开关网关实现(Redis SETNX)
│   ├── converter                                # 持久化对象转换器(PO ↔ Domain)
│   │   ├── InventoryConverter.java
│   │   ├── LockOrderConverter.java
│   │   ├── DeductionDetailConverter.java
│   │   └── RefundDetailConverter.java
│   ├── dataobject                               # 数据对象(PO, 对应DB表)
│   │   ├── InventoryPO.java                     # 对应inventory表
│   │   ├── LockInventoryOrderPO.java            # 对应lock_inventory_order表
│   │   ├── DeductionDetailPO.java               # 对应deduction_detail表
│   │   └── RefundDetailPO.java                  # 对应refund_detail表
│   ├── mapper                                   # MyBatis-Plus Mapper
│   │   ├── InventoryMapper.java
│   │   ├── LockOrderMapper.java
│   │   ├── DeductionDetailMapper.java
│   │   └── RefundDetailMapper.java
│   ├── lua                                      # Lua脚本定义
│   │   ├── deduct.lua                           # 原子扣减
│   │   ├── init_buckets.lua                     # 原子初始化分桶
│   │   ├── cleanup_buckets.lua                  # 原子清理分桶
│   │   └── incr_refund.lua                      # 原子INCR回补
│   ├── event                                    # 事件实现
│   │   └── DomainEventPublisherImpl.java        # 领域事件发布实现
│   └── config                                   # 配置类
│       ├── RedisConfig.java                     # Redis配置
│       ├── RedissonConfig.java                  # Redisson配置
│       ├── MybatisPlusConfig.java               # MyBatis-Plus配置
│       ├── ThreadPoolConfig.java                # 线程池配置
│       └── StoreProperties.java                 # 业务配置属性(分桶数/合并延迟/自动锁库存等)
│
├── common                                       # 公共模块
│   ├── enums                                    # 枚举
│   │   ├── DeductionStatusEnum.java             # 扣减明细状态
│   │   ├── DeductPathEnum.java                  # 扣减路径
│   │   ├── LockOrderStatusEnum.java             # 锁库存单据状态
│   │   └── ErrorCodeEnum.java                   # 错误码
│   ├── exception                                # 异常
│   │   ├── InventoryException.java              # 库存业务异常基类
│   │   ├── InsufficientStockException.java      # 库存不足
│   │   ├── LockQuantityExceededException.java   # 锁库存额度不足
│   │   ├── MergeCommitFailedException.java      # 合并提交失败
│   │   └── CompensateMergeFailedException.java  # 补偿合并失败
│   ├── result                                   # 统一返回
│   │   └── Result.java                          # 统一响应结构体
│   └── util                                     # 工具类
│       └── IdGenerator.java                     # 雪花算法ID生成器
│
└── StoreApplication.java                        # Spring Boot 启动类
```

## 资源文件结构

```
src/main/resources/
├── application.properties                       # 应用配置
├── application-dev.properties                   # 开发环境配置
├── application-prod.properties                  # 生产环境配置
├── lua/                                         # Lua脚本资源
│   ├── deduct.lua
│   ├── init_buckets.lua
│   ├── cleanup_buckets.lua
│   └── incr_refund.lua
└── db/                                          # 数据库脚本
    └── schema.sql                               # 建表DDL
```

## 分层依赖规则

```
┌──────────┐
│ Adapter  │  依赖 App + Domain + Common
└────┬─────┘
     │
┌────▼─────┐
│   App    │  依赖 Domain + Infrastructure接口 + Common
└────┬─────┘
     │
┌────▼─────┐
│  Domain  │  不依赖任何上层，只依赖 JVM 基础库 + Common
└────▲─────┘
     │
┌────┴──────────┐
│Infrastructure │  实现 Domain 定义的接口，依赖 Domain + 技术框架
└───────────────┘
```

**硬性规则**：
- Domain 层不能 import Spring、MyBatis、Redisson 等框架类
- Adapter → App → Domain 是主调用链，不允许反向
- Infrastructure 实现 Domain 接口，通过 Spring DI 注入给 App 层使用

## CQRS 分离策略

| 操作类型 | 链路 | 说明 |
|----------|------|------|
| 写操作 | Adapter(Controller+CmdExe) → App(AppService) → Domain(DomainService+Aggregate) → Infrastructure(RepositoryImpl) | 走完整DDD链路 |
| 读操作 | Adapter(Controller+QueryExe) → Infrastructure(Mapper/RepositoryImpl) | 可跳过Domain层，直接查PO |

## 对象流转路径

```
Controller 入参(Request DTO)
  → CmdExe 转换为 Command
    → AppService 编排
      → Assembler 转换为 Domain Entity/ValueObject
        → DomainService 执行业务逻辑
          → Repository 保存(Domain对象 → Converter → PO → Mapper)
      → Assembler 转换为 Response VO
    → Result 封装统一响应
```

## 文件数量统计

| 层 | 包 | 文件数 |
|----|-----|--------|
| Adapter | controller | 2 |
| Adapter | command | 5 |
| Adapter | query | 3 |
| App | service | 6 + 6(impl) |
| App | assembler | 1 |
| App | task | 4 |
| Domain | inventory | 6(vo) + 1(aggregate) + 1(entity) + 1(service) + 2(repo) + 4(gateway) + 1(event) = 16 |
| Domain | deduction | 7(vo) + 1(aggregate) + 1(entity) + 1(service) + 1(repo) + 2(event) = 13 |
| Domain | refund | 3(vo) + 1(entity) + 1(service) + 1(repo) = 6 |
| Domain | routing | 2(vo) + 1(service) = 3 |
| Infrastructure | repository | 4 |
| Infrastructure | gateway | 4 |
| Infrastructure | converter | 4 |
| Infrastructure | dataobject | 4 |
| Infrastructure | mapper | 4 |
| Infrastructure | lua | 4 |
| Infrastructure | event | 1 |
| Infrastructure | config | 5 |
| Common | enums | 4 |
| Common | exception | 5 |
| Common | result | 1 |
| Common | util | 1 |
| **合计** | | **~97个Java文件 + 4个Lua脚本 + 1个SQL脚本** |
