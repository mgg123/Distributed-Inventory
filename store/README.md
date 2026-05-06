# Distributed-Inventory

基于 Redis 分布式强一致库存扣减系统，面向热点深库存下单抢购场景（直播带货、秒杀活动），通过 Redis 分桶预扣减 + 异步合并提交架构，在保证**强一致性**（绝对不超卖不少卖）的前提下，将单 SKU 扣减 TPS 提升至 10000+。

## 核心特性

- **Redis 分桶扣减** — 将库存按 lockOrderId 维度分散到多个 Redis Bucket，Lua 脚本保证原子扣减，消除单 Key 热点
- **异步合并提交** — 扣减请求先在 Redis 完成预扣减并记录 PENDING 明细，后台定时任务批量合并提交到 DB，合并窗口期 ≤ 2 秒
- **自动锁库存** — 监控可用额度自动触发锁库存，消除合并提交空窗期，保证扣减链路始终有 Redis 分桶可用
- **DB 降级兜底** — Redis 不可用时自动降级到数据库直接扣减，核心链路不中断
- **紧急降级** — Redis 故障时一键释放 lq，使 DB 降级路径恢复可用额度
- **四象限库存模型** — 可售库存(sq) / 预扣库存(wq) / 占用库存(oq) / 预锁库存(lq)，覆盖下单→付款→退款全生命周期
- **强一致性保障** — 补偿合并、崩溃恢复、幂等防护、分布式锁多重机制确保数据一致
- **可观测性** — Prometheus 指标 + Actuator 健康检查 + Swagger API 文档

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 基础运行时 |
| Spring Boot | 4.0.6 | 应用框架 |
| Redis + Redisson | 4.3.1 | 分布式缓存 & 分布式锁 |
| MySQL | 8.x | 持久化存储 |
| MyBatis-Plus | 3.5.16 | ORM 框架 |
| Lua Script | - | Redis 原子操作脚本 |
| SpringDoc OpenAPI | 2.8.8 | API 文档 |
| Micrometer + Prometheus | - | 监控指标 |
| Lombok | - | 代码简化 |

## 系统架构

### 分层架构 (DDD + COLA)

```
┌──────────────────────────────────────────────────────────┐
│                     适配层 (Adapter)                       │
│   InventoryWriteController / InventoryReadController      │
│   CQRS 分离: 写 CmdExe + 读 QueryExe                      │
└──────────────────────────┬───────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────┐
│                     应用层 (App)                           │
│   AppService 编排 + 定时任务 + 领域事件监听                  │
└──────────────────────────┬───────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────┐
│                    领域层 (Domain)                          │
│   聚合根 + 实体 + 值对象 + 领域服务 + 仓储接口 + 网关接口      │
└──────────────────────────▲───────────────────────────────┘
                           │
┌──────────────────────────┴───────────────────────────────┐
│                 基础设施层 (Infrastructure)                 │
│   仓储实现 + 网关实现 + Mapper + Lua 脚本 + 配置             │
└──────────────────────────────────────────────────────────┘
```

### 限界上下文

| 限界上下文 | 包路径 | 核心聚合 | 职责 |
|-----------|--------|---------|------|
| Inventory | domain.inventory | InventoryAggregate | 锁库存、自动锁库存、紧急降级 |
| Deduction | domain.deduction | DeductionDetailAggregate | 分桶扣减、合并提交、补偿合并 |
| Routing | domain.routing | - | 活跃 lockOrder 路由解析 |
| Refund | domain.refund | RefundDetailAggregate | 取消/退款回补管理 |

### 库存模型

```
inventory 表四象限模型:
┌─────────────────────────────────────────────────┐
│  sq (可售库存)  ──锁定──▶  lq (预锁库存)          │
│       │                        │                 │
│    扣减合并                   Redis分桶扣减       │
│       │                        │                 │
│       ▼                        ▼                 │
│  wq (预扣库存)  ──付款──▶  oq (占用库存)          │
│                         退款回补 ▲                │
│                                │                 │
│                          REFUNDED                │
└─────────────────────────────────────────────────┘
```

### 扣减流程

```
1. 锁库存: sq ──lq+──▶ Redis分桶初始化
2. 扣减:   Redis Lua原子扣减 → PENDING明细
3. 合并:   定时任务批量合并 → wq+ → MERGED明细
4. 确认:   付款确认 → wq- oq+ → OCCUPIED明细
5. 退款:   退款 → oq- sq+ → REFUNDED明细
6. 降级:   Redis不可用 → DB直接扣减sq → DIRECT_DB明细
```

## 项目结构

```
com.mgg.exp.store
├── adapter                          # 适配层
│   ├── dto/                         # 数据传输对象
│   │   ├── command/                 # 写命令 (Lock/Deduct/Refund/Cancel/Confirm/Merge/Emergency)
│   │   └── query/                   # 查询对象 (InventoryVO/DeductionDetailVO/LockOrderVO)
│   ├── exe/                         # 命令/查询执行器
│   └── web/                         # REST Controller
│       ├── InventoryWriteController # 写操作接口
│       └── InventoryReadController  # 读操作接口
│
├── app                              # 应用层
│   ├── listener/                    # 领域事件监听
│   ├── scheduler/                   # 定时任务
│   │   ├── MergeSchedulerTask       # 合并提交调度
│   │   ├── CompensateTask           # 补偿扫描
│   │   ├── LockExpireTask           # 锁超时释放
│   │   └── AutoLockCheckTask        # 自动锁库存检测
│   └── service/                     # 应用服务
│       ├── InventoryLockAppService  # 锁库存
│       ├── InventoryDeductAppService# 扣减
│       ├── InventoryRefundAppService# 回补(取消/退款/确认)
│       ├── InventoryMergeAppService # 合并提交
│       ├── AutoLockAppService       # 自动锁库存
│       └── EmergencyAppService      # 紧急降级
│
├── domain                           # 领域层
│   ├── inventory/                   # 库存限界上下文
│   │   ├── aggregate/               # InventoryAggregate
│   │   ├── entity/                  # LockInventoryOrder
│   │   ├── valueobject/             # Quantity/SkuId/LockOrderId/LockResult/...
│   │   ├── service/                 # InventoryDomainService
│   │   ├── repository/              # 仓储接口
│   │   └── event/                   # AutoLockEvent
│   ├── deduction/                   # 扣减限界上下文
│   │   ├── aggregate/               # DeductionDetailAggregate
│   │   ├── entity/                  # DeductionDetail
│   │   ├── valueobject/             # DeductPath/DeductionStatus/DeductResult/MergeResult/...
│   │   ├── service/                 # DeductionDomainService
│   │   ├── repository/              # 仓储接口
│   │   └── event/                   # InventoryDeductedEvent/MergeCommittedEvent
│   ├── refund/                      # 回补限界上下文
│   │   ├── entity/                  # RefundDetail
│   │   ├── valueobject/             # RefundId/RefundQuantity/RefundResult/...
│   │   ├── service/                 # RefundDomainService
│   │   └── repository/              # 仓储接口
│   ├── routing/                     # 路由限界上下文
│   │   ├── valueobject/             # ActiveLockRoute/RouteResolveResult
│   │   └── service/                 # RoutingDomainService
│   └── gateway/                     # 网关接口定义
│       ├── RedisBucketGateway       # Redis分桶网关
│       ├── ActiveLockRouterGateway  # 活跃路由网关
│       ├── DistributedLockGateway   # 分布式锁网关
│       └── EmergencyDegradeGateway  # 紧急降级开关网关
│
├── infrastructure                   # 基础设施层
│   ├── config/                      # 配置类
│   ├── converter/                   # PO ↔ Domain 转换器
│   ├── dataobject/                  # 持久化对象 (PO)
│   ├── gateway/                     # 网关实现
│   ├── mapper/                      # MyBatis-Plus Mapper
│   ├── repository/                  # 仓储实现
│   ├── event/                       # 领域事件发布实现
│   ├── health/                      # Redis 健康检查
│   └── metrics/                     # Prometheus 指标
│
├── common                           # 公共模块
│   ├── enums/                       # 枚举 (DeductionStatus/DeductPath/ErrorCode/...)
│   ├── exception/                   # 异常 (InsufficientStock/MergeFailed/...)
│   ├── result/                      # 统一响应 Result
│   └── util/                        # 工具类 (IdGenerator)
│
└── StoreApplication.java            # Spring Boot 启动类
```

## API 接口

### 写操作

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/inventory/lock` | 锁库存（sq → lq，初始化 Redis 分桶） |
| POST | `/api/v1/inventory/lock/{lockOrderId}/release` | 释放锁库存（触发合并提交） |
| POST | `/api/v1/inventory/deduct` | 扣减库存（优先 Redis 分桶，降级走 DB） |
| POST | `/api/v1/inventory/cancel` | 取消订单（根据明细状态执行回补） |
| POST | `/api/v1/inventory/refund` | 退款（oq → sq） |
| POST | `/api/v1/inventory/confirm` | 付款确认（wq → oq） |
| POST | `/api/v1/inventory/merge` | 手动触发合并提交（运维接口） |
| POST | `/api/v1/inventory/emergency-unlock` | 紧急解锁（Redis 不可用时释放 lq） |

### 读操作

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/inventory/{skuId}` | 查询 SKU 库存信息 |
| GET | `/api/v1/inventory/deduction/{orderId}` | 查询扣减明细 |
| GET | `/api/v1/inventory/lock-order/{lockOrderId}` | 查询锁库存单据 |

### API 文档

启动应用后访问 Swagger UI: `http://localhost:8080/swagger-ui.html`

## 数据库设计

### inventory（库存主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | SKU 标识，主键 |
| sq | INT | 可售库存 (Saleable Quantity) |
| wq | INT | 预扣库存 (Withheld Quantity) |
| oq | INT | 占用库存 (Occupied Quantity) |
| lq | INT | 预锁库存 (Locked Quantity) |

### lock_inventory_order（锁库存单据表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) | 单据 ID，主键 |
| sku_id | BIGINT | 商品 SKU |
| lock_quantity | INT | 锁定数量 |
| bucket_info | JSON | Redis 分桶信息 |
| expire_time | DATETIME | 过期时间 |
| status | VARCHAR(16) | ACTIVE / ARCHIVED |
| idempotent_key | VARCHAR(128) | 幂等键（唯一索引） |

### deduction_detail（扣减明细表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) | 单据 ID，主键 |
| sku_id | BIGINT | 商品 SKU |
| quantity | INT | 扣减数量 |
| deduct_path | VARCHAR(16) | MERGE_BUCKETS / DIRECT_DB |
| status | VARCHAR(16) | PENDING → MERGED → OCCUPIED → CANCELLED/REFUNDED |
| order_id | VARCHAR(64) | 关联订单 ID |
| lock_order_id | VARCHAR(64) | 关联锁库存单据 ID |

### refund_detail（回补明细表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) | 单据 ID，主键 |
| sku_id | BIGINT | 商品 SKU |
| refund_quantity | INT | 回补数量 |
| deduct_path | VARCHAR(16) | 同原明细扣减路径 |
| order_id | VARCHAR(64) | 关联订单 ID |
| ref_detail_id | VARCHAR(64) | 关联原扣减明细 ID |

## Redis Lua 脚本

| 脚本 | 说明 |
|------|------|
| `deduct.lua` | 原子扣减：检查当前桶余量 ≥ 请求数量，DECRBY 扣减，桶耗尽返回 2 |
| `init_buckets.lua` | 原子初始化：批量 SET 各桶初始值 + 元数据 Key + 总余量 Key |
| `cleanup_buckets.lua` | 原子清理：批量 DEL 所有桶 Key + 元数据 Key + 总余量 Key |
| `incr_refund.lua` | 原子回补：检查元数据存在后 INCRBY 回补桶余量和总余量 |

## 配置说明

```yaml
store:
  bucket:
    count: 16                    # Redis 分桶数量
    fallover-max-retries: 3      # 分桶故障转移最大重试次数
  merge:
    delay-ms: 1000               # 合并提交延迟（毫秒）
    idle-qps-threshold: 100      # 空闲 QPS 阈值（低于此值触发合并）
  auto-lock:
    enabled: true                # 是否启用自动锁库存
    reserve-ratio: 0.1           # DB 降级预留比例
    min-lock-quantity: 100       # 最小有效锁定量
    max-active: 2                # 最大活跃 lockOrder 数
    trigger-ratio: 0.3           # 触发自动锁库存的可用额度比例
    check-interval-ms: 500       # 检测间隔（毫秒）
    expire-seconds: 300          # 锁库存过期时间（秒）
  redis:
    fail-threshold: 5            # Redis 连续失败阈值（触发降级）
  lock:
    wait-time-seconds: 10        # 分布式锁等待时间（秒）
    lease-time-seconds: 30       # 分布式锁持有时间（秒）
```

## 快速开始

### 环境依赖

- JDK 21+
- MySQL 8.x
- Redis 7.x+

### 数据库初始化

```bash
mysql -u root -p < src/main/resources/sql/schema.sql
```

### 配置修改

修改 `src/main/resources/application.yml` 中的数据库和 Redis 连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/store?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: 123456
  data:
    redis:
      host: 127.0.0.1
      port: 6379
```

### 构建与运行

```bash
# 构建
mvn clean package -DskipTests

# 运行
java -jar target/store-0.0.1-SNAPSHOT.jar
```

### 运行测试

测试使用 H2 内存数据库 + Embedded Redis，无需外部依赖：

```bash
mvn test
```

## 监控

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 健康检查 |
| `/actuator/prometheus` | Prometheus 指标 |
| `/actuator/metrics` | Metrics 端点 |
| `/api-docs` | OpenAPI 文档 |

## 一致性保障机制

| 机制 | 说明 |
|------|------|
| Lua 原子脚本 | Redis 扣减/初始化/清理/回补均通过 Lua 脚本保证原子性 |
| DB 行锁 | InnoDB 行锁保证并发 UPDATE 串行执行 |
| 幂等防护 | idempotent_key 唯一索引 + refund_request_id 幂等键 |
| 分布式锁 | Redisson RLock 防止合并提交并发冲突 |
| 补偿合并 | 定时扫描孤立 PENDING 明细，补偿执行合并提交 |
| 崩溃恢复 | 应用启动时检查并修复缺失的路由缓存和未完成合并 |
| 紧急降级 | Redis 故障时一键释放 lq，恢复 DB 降级路径可用额度 |
| Redis 健康检查 | 持续探测 Redis 可用性，连续失败自动切换降级模式 |

## License

Private - All Rights Reserved
