---
name: "ddd-cola-architecture"
description: "基于DDD领域驱动设计和COLA架构的项目结构设计规范。在新建项目、组织代码包结构、进行分层架构设计、或用户提到DDD/COLA/领域驱动设计/分层架构时调用此skill。"
---

# DDD + COLA 架构项目结构设计规范

本 skill 融合 **DDD（Domain-Driven Design，领域驱动设计）** 核心思想和阿里巴巴开源的 **COLA（Clean Object-oriented and Layered Architecture）** 框架，提供一套清晰的、可落地的项目分层架构规范，适用于中大型企业级应用开发。

---

## 一、核心理念

### 1.1 DDD 核心思想
- **战略设计**：限界上下文（Bounded Context）、上下文映射（Context Map）
- **战术设计**：实体（Entity）、值对象（Value Object）、聚合（Aggregate）、领域服务（Domain Service）、仓储（Repository）
- **统一语言（Ubiquitous Language）**：团队使用同一套术语，代码即文档

### 1.2 COLA 核心原则
- **分层隔离**：严格的分层依赖规则，上层可依赖下层，下层不可反向依赖上层
- **扩展点机制**：通过 SPI（Extension Point）实现业务扩展，而非 if-else
- **CQRS 可选支持**：命令查询职责分离，读写模型可独立演进
- **应用层薄层原则**：Application 层仅做编排/调度，不含业务规则

---

## 二、COLA 标准项目分层结构

### 2.1 Maven 多模块结构（推荐）

```
${project-name}
├── ${project-name}-adapter          # 适配层（入口）
├── ${project-name}-app              # 应用层
├── ${project-name}-domain           # 领域层（核心）
├── ${project-name}-infrastructure   # 基础设施层
└── start                            # 启动模块（Spring Boot 入口）
```

### 2.2 单模块包结构（小型项目可用）

```
com.mgg.exp.store
├── adapter                                  # 适配层
│   ├── command                              # Command 服务入口（写）
│   │   └── InventoryLockCmdExe.java
│   ├── query                                # Query 服务入口（读）
│   │   └── InventoryQueryExe.java
│   └── controller                           # REST Controller
│       └── InventoryController.java
├── app                                      # 应用层
│   ├── service                              # 应用服务（编排）
│   │   ├── InventoryDeductionAppService.java
│   │   └── impl
│   │       └── InventoryDeductionAppServiceImpl.java
│   ├── assembler                            # 对象转换器（DTO ↔ Domain）
│   │   └── InventoryAssembler.java
│   └── task                                 # 定时任务 / 合并调度
│       └── MergeSchedulerTask.java
├── domain                                   # 领域层（核心，无外部依赖）
│   ├── inventory                            # 库存限界上下文
│   │   ├── aggregate                        # 聚合
│   │   │   └── InventoryAggregate.java
│   │   ├── entity                           # 实体
│   │   │   ├── InventorySnapshot.java
│   │   │   └── DeductionDetail.java
│   │   ├── valueobject                      # 值对象
│   │   │   ├── InventoryId.java
│   │   │   ├── SkuId.java
│   │   │   └── DeductionAmount.java
│   │   ├── service                          # 领域服务
│   │   │   └── InventoryDomainService.java
│   │   ├── repository                       # 仓储接口（仅定义，实现在infrastructure）
│   │   │   ├── InventoryRepository.java
│   │   │   └── DeductionDetailRepository.java
│   │   └── event                            # 领域事件
│   │       ├── InventoryDeductedEvent.java
│   │       └── MergeCommittedEvent.java
│   └── gateway                              # 外部服务网关接口（仅定义）
│       └── RedisDeductionGateway.java
├── infrastructure                           # 基础设施层
│   ├── repository                           # 仓储实现
│   │   ├── InventoryRepositoryImpl.java
│   │   └── DeductionDetailRepositoryImpl.java
│   ├── gateway                              # 网关实现
│   │   └── RedisDeductionGatewayImpl.java
│   ├── converter                            # 持久化对象转换器（PO ↔ Domain）
│   │   └── InventoryConverter.java
│   ├── dataobject                           # 数据对象（PO）
│   │   ├── InventoryPO.java
│   │   └── DeductionDetailPO.java
│   ├── event                                # 事件实现
│   │   ├── EventPublisherImpl.java
│   │   └── EventHandlerImpl.java
│   └── config                               # 配置类
│       ├── RedisConfig.java
│       └── ThreadPoolConfig.java
├── common                                   # 公共模块
│   ├── enums                                # 枚举
│   │   ├── DeductionStatusEnum.java
│   │   └── DeductionTypeEnum.java
│   ├── exception                            # 异常
│   │   ├── InventoryException.java
│   │   └── ErrorCode.java
│   ├── result                               # 统一返回
│   │   └── Result.java
│   └── util                                 # 工具类（尽量少）
└── StoreApplication.java                    # Spring Boot 启动类
```

---

## 三、各层职责与规范

### 3.1 Adapter 适配层

**职责**：接收外部请求，协议转换，调用应用层服务。

| 要素 | 规范 |
|------|------|
| 包含 | Controller, Command Service, Query Service, RPC 实现, MQ Listener |
| 不包含 | 业务逻辑、数据库操作 |
| 入参 | 使用独立的 DTO/Command/Query 对象，不与 Domain 对象混用 |
| 出参 | 使用独立的 VO/Response 对象 |

```java
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryDeductionAppService deductionAppService;

    @PostMapping("/deduct")
    public Result<DeductionResponse> deduct(@Valid @RequestBody DeductionRequest request) {
        // 1. 协议转换（DTO → Command）
        DeductionCmd cmd = InventoryAssembler.toCommand(request);
        // 2. 调用应用服务
        DeductionResult result = deductionAppService.deduct(cmd);
        // 3. 结果封装（Result → VO）
        return Result.success(InventoryAssembler.toResponse(result));
    }
}
```

### 3.2 App 应用层

**职责**：业务流程编排、事务管理、权限校验、调用领域服务。

| 要素 | 规范 |
|------|------|
| 包含 | AppService(接口+实现), Assembler, ScheduledTask |
| 不包含 | 领域业务规则（这些属于 domain 层） |
| 事务 | 仅在 AppService 层管理 `@Transactional` |

```java
public interface InventoryDeductionAppService {
    DeductionResult deduct(DeductionCmd cmd);
}

@Service
@Transactional
public class InventoryDeductionAppServiceImpl implements InventoryDeductionAppService {

    private final InventoryDomainService inventoryDomainService;
    private final DeductionDetailRepository detailRepository;
    private final RedisDeductionGateway redisGateway;

    @Override
    public DeductionResult deduct(DeductionCmd cmd) {
        // 1. Redis 预扣减
        boolean redisSuccess = redisGateway.decrBucket(cmd.getSkuId(), cmd.getQuantity());
        if (!redisSuccess) {
            // 2. 降级：走 DB 直接扣减
            return inventoryDomainService.directDeduct(cmd.getSkuId(), cmd.getQuantity());
        }
        // 3. 插入扣减明细（领域对象）
        DeductionDetail detail = inventoryDomainService.createDeductionDetail(
            cmd.getOrderId(), cmd.getSkuId(), cmd.getQuantity()
        );
        detailRepository.save(detail);
        // 4. 发布领域事件
        DomainEventPublisher.publish(new InventoryDeductedEvent(detail));
        return DeductionResult.success(detail);
    }
}
```

### 3.3 Domain 领域层（核心）

**职责**：封装核心业务规则和领域模型，**不依赖任何外部框架**。

| 要素 | 规范 |
|------|------|
| 包含 | Entity, ValueObject, Aggregate, DomainService, Repository接口, DomainEvent |
| 不包含 | 任何框架注解（@Service/@Component等技术注解除外），数据库访问实现 |
| 依赖 | 只依赖 JVM 基础库和 common 模块 |

#### 实体（Entity）
- 有唯一标识（ID）
- 有生命周期，可变
- 包含行为方法，不是贫血模型

#### 值对象（Value Object）
- 没有唯一标识，不可变
- 通过属性值判断相等性
- 可被替换，自身无生命周期

#### 聚合（Aggregate）
- 一组相关对象的集合，有聚合根（Aggregate Root）
- 外部只能通过聚合根访问聚合内对象
- 聚合内部保证一致性

```java
// 聚合根
public class InventoryAggregate {
    private InventoryId id;
    private SkuId skuId;
    private Quantity sqAmount;       // 可售库存
    private Quantity wqAmount;       // 预扣库存
    private Quantity oqAmount;       // 占用库存
    private Quantity lqAmount;       // 预锁库存

    // 锁库存（领域行为，包含业务规则）
    public LockResult lock(Quantity lockAmount) {
        if (sqAmount.subtract(lqAmount).isLessThan(lockAmount)) {
            return LockResult.insufficient();
        }
        this.lqAmount = lqAmount.add(lockAmount);
        return LockResult.success(this.id, lockAmount);
    }

    // 合并提交（领域行为）
    public MergeResult mergeCommit(Quantity netDeduction) {
        if (netDeduction.isNegative()) {
            throw new InventoryException("net deduction must be positive");
        }
        this.sqAmount = sqAmount.subtract(netDeduction);
        this.wqAmount = wqAmount.add(netDeduction);
        return MergeResult.success();
    }
}
```

#### 值对象示例

```java
public class Quantity {
    private final int value;

    public Quantity(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("quantity must >= 0");
        }
        this.value = value;
    }

    public Quantity add(Quantity other) { return new Quantity(this.value + other.value); }
    public Quantity subtract(Quantity other) { return new Quantity(this.value - other.value); }
    public boolean isLessThan(Quantity other) { return this.value < other.value; }
    public boolean isNegative() { return this.value < 0; }
    // equals / hashCode / toString
}
```

#### 仓储接口（只定义，不实现）

```java
public interface InventoryRepository {
    Optional<InventoryAggregate> findBySkuId(SkuId skuId);
    void save(InventoryAggregate aggregate);
    void updateWithLock(InventoryAggregate aggregate);  // 乐观锁更新
}

public interface DeductionDetailRepository {
    void save(DeductionDetail detail);
    List<DeductionDetail> findPendingBySkuId(SkuId skuId);
    void batchUpdateStatus(List<DeductionDetail> details, DeductionStatusEnum status);
}
```

#### 网关接口（只定义，不实现）

```java
public interface RedisDeductionGateway {
    boolean decrBucket(SkuId skuId, Quantity quantity);
    void incrBucket(SkuId skuId, Quantity quantity);          // 回补
    void initBuckets(LockId lockId, SkuId skuId, Quantity total, int bucketCount);
    void disableBuckets(LockId lockId, SkuId skuId);
}
```

### 3.4 Infrastructure 基础设施层

**职责**：提供技术实现，实现 Domain 层定义的接口。

| 要素 | 规范 |
|------|------|
| 包含 | RepositoryImpl, GatewayImpl, DataObject, Converter, Config, Event实现 |
| 依赖 | Domain层接口，具体技术框架（Spring Data, MyBatis, Redisson） |

```java
@Repository
public class InventoryRepositoryImpl implements InventoryRepository {

    private final JdbcTemplate jdbcTemplate;   // 或 MyBatis Mapper

    @Override
    public Optional<InventoryAggregate> findBySkuId(SkuId skuId) {
        InventoryPO po = jdbcTemplate.queryForObject(
            "SELECT * FROM inventory WHERE sku_id = ?", InventoryPO.class, skuId.getValue()
        );
        return Optional.ofNullable(po).map(InventoryConverter::toDomain);
    }

    @Override
    public void updateWithLock(InventoryAggregate aggregate) {
        int rows = jdbcTemplate.update(
            "UPDATE inventory SET sq = ?, wq = ?, oq = ?, lq = ?, version = version + 1, update_time = NOW() " +
            "WHERE id = ? AND version = ?",
            aggregate.getSqAmount().getValue(),
            aggregate.getWqAmount().getValue(),
            aggregate.getOqAmount().getValue(),
            aggregate.getLqAmount().getValue(),
            aggregate.getId().getValue(),
            aggregate.getVersion()
        );
        if (rows == 0) {
            throw new OptimisticLockException("inventory update conflict");
        }
    }
}
```

---

## 四、分层依赖规则

```
┌──────────┐
│ Adapter  │  依赖 App + Domain
└────┬─────┘
     │
┌────▼─────┐
│   App    │  依赖 Domain + Infrastructure 接口
└────┬─────┘
     │
┌────▼─────┐
│  Domain  │  不依赖任何上层，只依赖 JVM 基础库
└────▲─────┘
     │
┌────┴─────┐
│Infrastructure│  实现 Domain 定义的接口
└──────────┘
```

**硬性规则**：
- Domain 层**不能 import** Spring、MyBatis、Redisson 等框架类（`@Component` / `@Service` 等轻量注解除外）
- Adapter → App → Domain 是主调用链，不允许反向
- Infrastructure 实现 Domain 接口，通过 Spring DI 注入给 App 层使用
- 跨限界上下文通信通过 **领域事件** 或 **应用服务编排** 完成，不可直接调用对方的 Repository

---

## 五、CQRS（命令查询职责分离）模式

对于读写性能差异大的场景（如库存扣减），推荐 CQRS：

```
Write Model（命令）                    Read Model（查询）
─────────────────                    ─────────────────
Adapter: 写 Controller                Adapter: 读 Controller
    │                                     │
App:   写 AppService                  App:   读 AppService (可选)
    │                                     │
Domain: InventoryAggregate             Infrastructure: 直接查 PO/VO
    │                                     │
Infrastructure: RepositoryImpl          Infrastructure: Mapper/R2DBC 等
```

- **写操作**：走完整 DDD 链路（Adapter → App → Domain → Repository）
- **读操作**：可跳过 Domain 层，直接从 Adapter 到 Infrastructure 查询（通过 DAO/Mapper）
- 读写模型可独立优化，读侧可用 Redis 缓存、Elasticsearch 等

---

## 六、领域事件

用于 **限界上下文间通信** 和 **异步业务流程**。

```java
// 1. 定义事件
public class InventoryDeductedEvent extends DomainEvent {
    private final OrderId orderId;
    private final SkuId skuId;
    private final Quantity deductedAmount;
}

// 2. 发布事件（App 层）
domainEventPublisher.publish(new InventoryDeductedEvent(orderId, skuId, qty));

// 3. 处理事件（同模块或独立模块）
@EventListener
public void handleInventoryDeducted(InventoryDeductedEvent event) {
    // 触发合并检查 / 通知订单模块 / 发送 MQ 消息
}
```

---

## 七、扩展点机制（COLA 核心特色）

当同一业务流程在不同场景有不同实现时，使用扩展点代替 if-else：

```java
// 1. 定义扩展点接口（Domain 层）
public interface DeductionStrategyExtPt {
    DeductionResult deduct(SkuId skuId, Quantity quantity);
}

// 2. 不同场景的实现（Infrastructure 或独立 extension 模块）
@Extension(bizCode = "seckill")    // 秒杀场景
public class SeckillDeductionStrategy implements DeductionStrategyExtPt { ... }

@Extension(bizCode = "normal")     // 普通场景
public class NormalDeductionStrategy implements DeductionStrategyExtPt { ... }

// 3. 使用扩展点（App 层）
DeductionStrategyExtPt strategy = extensionExecutor.execute(
    DeductionStrategyExtPt.class, bizScenario
);
strategy.deduct(skuId, quantity);
```

---

## 八、对库存扣减系统的 COLA 建模建议

基于 spec.md 中的库存扣减需求，DDD + COLA 建模如下：

### 8.1 限界上下文划分

| 限界上下文 | 职责 | 核心聚合 |
|-----------|------|---------|
| Inventory（库存上下文） | 库存锁/扣减/合并/回补 | InventoryAggregate |
| DeductionDetail（明细上下文） | 扣减明细记录、生命周期管理 | DeductionDetailAggregate |
| Bucket（分桶上下文） | Redis 分桶管理、预扣减计数 | Bucket (值对象集合) |

### 8.2 核心聚合设计

```
InventoryAggregate (聚合根)
├── InventoryId (值对象)
├── SkuId (值对象)
├── Quantity: sq, wq, oq, lq (值对象)
├── Version (值对象，乐观锁)
└── 行为:
    ├── lock(Quantity) → LockResult
    ├── mergeCommit(Quantity) → MergeResult
    └── release(Quantity) → ReleaseResult

DeductionDetailAggregate (聚合根)
├── DetailId (值对象)
├── OrderId (值对象)
├── SkuId (值对象)
├── Quantity (值对象)
├── DeductionType (枚举)
├── Status (枚举，含状态机转换)
└── 行为:
    ├── cancel() → CancelResult
    └── markMerged() → MergeResult
```

### 8.3 领域服务

```java
// 库存领域服务：处理跨聚合的库存变更逻辑
public class InventoryDomainService {
    public DeductionDetail createDeductionDetail(OrderId orderId, SkuId skuId, Quantity qty) { ... }
    public MergeResult mergeDeductions(InventoryAggregate inventory, List<DeductionDetail> details) { ... }
}
```

---

## 九、COLA 模块依赖（Maven pom.xml 示例）

```xml
<!-- domain 模块：零外部依赖 -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- infrastructure 模块：实现 domain 接口 -->
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>${project.name}-domain</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- app 模块：编排 domain + infrastructure -->
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>${project.name}-domain</artifactId>
</dependency>
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>${project.name}-infrastructure</artifactId>
</dependency>
```

---

## 十、编码规约要点

### 10.1 命名约定

| 类型 | 后缀/前缀 | 示例 |
|------|----------|------|
| 应用服务接口 | XxxAppService | `InventoryDeductionAppService` |
| 应用服务实现 | XxxAppServiceImpl | `InventoryDeductionAppServiceImpl` |
| 领域服务 | XxxDomainService | `InventoryDomainService` |
| 仓储接口 | XxxRepository | `InventoryRepository` |
| 仓储实现 | XxxRepositoryImpl | `InventoryRepositoryImpl` |
| 网关接口 | XxxGateway | `RedisDeductionGateway` |
| 网关实现 | XxxGatewayImpl | `RedisDeductionGatewayImpl` |
| 数据对象 | XxxPO | `InventoryPO` |
| 数据传输对象 | XxxDTO / XxxCmd / XxxQuery | `DeductionCmd` |
| 转换器 | XxxAssembler / XxxConverter | `InventoryAssembler` |
| 聚合根 | XxxAggregate | `InventoryAggregate` |
| 扩展点接口 | XxxExtPt | `DeductionStrategyExtPt` |

### 10.2 对象流转路径

```
Controller 入参 DTO → AppService 入参 Cmd/Query
    → Assembler 转换为 Domain Entity/ValueObject
    → Domain 执行业务逻辑
    → Repository 保存（Domain对象 → Converter → PO）
    → 返回 Domain Result → Assembler 转换为 Response VO
```

### 10.3 异常处理

```java
// 统一错误码
public enum ErrorCode {
    INVENTORY_INSUFFICIENT("INV_001", "库存不足"),
    LOCK_CONFLICT("INV_002", "锁库存冲突"),
    MERGE_FAILED("INV_003", "合并提交失败");

    private final String code;
    private final String message;
}

// 领域异常
public class InventoryException extends RuntimeException {
    private final ErrorCode errorCode;
}
```

---

## 参考来源

- COLA 框架 GitHub: https://github.com/alibaba/COLA
- 《领域驱动设计》（Eric Evans）
- 《实现领域驱动设计》（Vaughn Vernon）
- 阿里巴巴 COLA 4.x 架构文档
