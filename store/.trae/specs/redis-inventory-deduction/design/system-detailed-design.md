# 基于Redis分布式强一致库存扣减系统 — 系统详细设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 系统名称 | 基于Redis分布式强一致库存扣减系统 |
| 文档版本 | V2.0 |
| 编写日期 | 2026-05-02 |
| 文档状态 | 更新（同步spec.md第六轮评审修复） |

## 1 引言

### 1.1 编写目的

本文档为"基于Redis分布式强一致库存扣减系统"的系统详细设计文档，在概要设计的基础上，对各模块的内部结构、核心算法、关键流程、异常处理和并发控制策略进行详细描述，为编码实现提供直接指导。

### 1.2 读者对象

- 后端开发工程师
- 测试工程师
- 代码审查人员

## 2 锁库存管理模块详细设计

### 2.1 类设计

```
LockService
├── lockInventory(skuId, lockQuantity, idempotentKey): LockOrderResult
├── releaseLock(lockOrderId): void
└── getLockOrder(lockOrderId): LockInventoryOrder

LockOrderMapper (extends BaseMapper<LockInventoryOrder>)
├── selectByIdempotentKey(idempotentKey): LockInventoryOrder
├── updateStatusToArchived(lockOrderId): int
└── updateMergeCompleted(lockOrderId): int

RedisBucketManager
├── initBuckets(lockOrderId, skuId, bucketCount, quantityPerBucket): void
├── cleanupBuckets(lockOrderId, bucketCount): void
└── getBucketMeta(lockOrderId): BucketMeta
```

### 2.2 锁库存操作严格时序

锁库存操作必须按以下严格顺序执行，任何前置步骤失败不得继续后续步骤：

```
┌─────────────────────────────────────────────────────────┐
│ Step 1: Redis Lua脚本原子初始化                           │
│   - 初始化N个分桶: inventory:lock:{lockOrderId}:bucket:0..N-1 │
│   - 初始化分桶索引: inventory:lock:{lockOrderId}:meta      │
│   - 初始化总余量: inventory:lock:{lockOrderId}:total_remaining │
│   → 失败: 直接返回错误，不执行后续步骤                      │
└────────────────────────┬────────────────────────────────┘
                         │ 成功
                         ▼
┌─────────────────────────────────────────────────────────┐
│ Step 2: DB事务内执行                                      │
│   a. UPDATE inventory SET lq = lq + #{actualLockQuantity} │
│      WHERE id = #{skuId} AND sq - lq >= #{actualLockQuantity} │
│   b. INSERT lock_inventory_order                          │
│      (status=ACTIVE, lock_quantity, idempotent_key,       │
│       merge_completed=false)                              │
│   → 事务失败: Lua脚本原子清理Redis分桶 + 事务自动回滚       │
└────────────────────────┬────────────────────────────────┘
                         │ 成功
                         ▼
┌─────────────────────────────────────────────────────────┐
│ Step 3: 原子更新路由缓存                                   │
│   SET inventory:active_lock:{skuId} = newLockOrderId      │
│   LPUSH inventory:active_lock_history:{skuId} = newLockOrderId │
│   → 必须在Step 1和Step 2全部完成后执行                     │
│   → 任何前置步骤失败，不更新路由缓存                        │
└─────────────────────────────────────────────────────────┘
```

### 2.3 锁库存操作伪代码

```java
public LockOrderResult lockInventory(Long skuId, int lockQuantity, String idempotentKey) {
    LockInventoryOrder existing = lockOrderMapper.selectByIdempotentKey(idempotentKey);
    if (existing != null) {
        if ("ARCHIVED".equals(existing.getStatus())) {
            return LockOrderResult.fail("LOCK_ORDER_ALREADY_ARCHIVED");
        }
        return LockOrderResult.success(existing.getId());
    }

    Inventory inventory = inventoryMapper.selectById(skuId);
    int available = inventory.getSq() - inventory.getLq();
    if (available < minLockQuantity) {
        return LockOrderResult.fail("LOCK_QUANTITY_EXCEEDED");
    }

    int actualLockQuantity = Math.min(lockQuantity, (int)(available * (1 - reserveRatio)));

    String lockOrderId = IdGenerator.nextId();

    // Step 1: Redis初始化
    try {
        redisBucketManager.initBuckets(lockOrderId, skuId, bucketCount, actualLockQuantity);
    } catch (Exception e) {
        return LockOrderResult.fail("REDIS_INIT_FAILED");
    }

    // Step 2: DB事务
    try {
        transactionTemplate.execute(status -> {
            int updated = inventoryMapper.increaseLq(skuId, actualLockQuantity);
            if (updated == 0) {
                throw new LockQuantityExceededException();
            }
            LockInventoryOrder order = new LockInventoryOrder();
            order.setId(lockOrderId);
            order.setSkuId(skuId);
            order.setLockQuantity(actualLockQuantity);
            order.setStatus("ACTIVE");
            order.setIdempotentKey(idempotentKey);
            order.setMergeCompleted(false);
            order.setExpireTime(LocalDateTime.now().plusMinutes(lockExpireMinutes));
            lockOrderMapper.insert(order);
            return null;
        });
    } catch (Exception e) {
        // 事务失败，回滚Redis
        redisBucketManager.cleanupBuckets(lockOrderId, bucketCount);
        return LockOrderResult.fail("DB_TRANSACTION_FAILED");
    }

    // Step 3: 更新路由缓存
    activeLockRouter.updateActiveRoute(skuId, lockOrderId);

    return LockOrderResult.success(lockOrderId);
}
```

### 2.4 锁库存释放流程

锁库存释放复用合并提交流程：

```java
public void releaseLock(String lockOrderId) {
    mergeScheduler.triggerMerge(lockOrderId);
}
```

释放时触发合并提交，将已卖出的部分从sq转移到wq，lq减去当前lockOrder的lockQuantity，未卖出的库存自然保留在sq中。

## 3 自动锁库存模块详细设计

### 3.1 类设计

```
AutoLockService
├── checkAndAutoLock(skuId): void
├── onDeductRequest(skuId): void
└── getActiveLockOrderCount(skuId): int

AutoLockCheckScheduler (定时任务)
└── checkAllActiveLockOrders(): void
```

### 3.2 连锁触发机制

自动锁库存的连锁触发采用异步事件驱动 + 同步快检混合模式：

```
┌─────────────────────────────────────────────────────────┐
│ 扣减请求路径 (同步快检)                                    │
│                                                          │
│  扣减请求 → 读取total_remaining                           │
│              │                                           │
│              ├── total_remaining > triggerRatio × lockQuantity │
│              │   → 正常扣减，不触发自动锁库存               │
│              │                                           │
│              └── total_remaining ≤ triggerRatio × lockQuantity │
│                  → 异步发送AutoLockEvent (fire-and-forget) │
│                  → 不阻塞扣减请求主路径                     │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ 后台定时任务 (兜底)                                       │
│                                                          │
│  每500ms扫描所有活跃lockOrder                              │
│  → 检查total_remaining是否低于阈值                        │
│  → 低于阈值则触发自动锁库存                                │
│  → 防止事件丢失                                           │
└─────────────────────────────────────────────────────────┘
```

### 3.3 滚动管线时序

```
T=0.0s  Lock-A 创建 (lq=10000, 16桶)     ← 扣减流量路由到Lock-A
T=0.5s  Lock-B 创建 (lq=10000, 16桶)     ← 自动锁库存提前创建
        → 路由缓存更新: active_lock:{skuId} = Lock-B
T=1.0s  Lock-A 合并提交开始
        → 失效Lock-A分桶索引
        → 扣减流量自动路由到Lock-B (不同lockOrderId, 独立分桶索引)
        → 无空窗期
T=1.0s  Lock-A 合并提交完成
T=1.5s  Lock-C 创建 (lq=10000, 16桶)     ← 自动锁库存提前创建
T=2.0s  Lock-B 合并提交开始
        → 扣减流量自动路由到Lock-C
        → 无空窗期
...循环
```

### 3.4 自动锁库存决策逻辑

```java
public void checkAndAutoLock(Long skuId) {
    int activeCount = getActiveLockOrderCount(skuId);
    if (activeCount >= maxActiveLockOrders) {
        return;
    }

    String activeLockOrderId = activeLockRouter.getActiveLockOrderId(skuId);
    if (activeLockOrderId != null) {
        Long remaining = redisBucketManager.getTotalRemaining(activeLockOrderId);
        Long lockQuantity = lockOrderMapper.selectById(activeLockOrderId).getLockQuantity();
        if (remaining != null && remaining > triggerRatio * lockQuantity) {
            return;
        }
    }

    lockService.lockInventory(skuId, autoLockQuantity,
        "AUTO-LOCK-" + skuId + "-" + System.currentTimeMillis());
}
```

### 3.5 预留DB降级额度

自动锁库存时保留一定比例的可用额度给DB降级路径：

```
actualLockQuantity = min(lockQuantity, (sq - lq) × (1 - reserveRatio))
```

其中 `reserveRatio` 默认0.1，即预留10%的可用额度给DB降级路径。

## 4 活跃lockOrder路由模块详细设计

### 4.1 类设计

```
ActiveLockRouter
├── getActiveLockOrderId(skuId): String
├── resolveLockOrderId(skuId, specifiedLockOrderId): String
├── updateActiveRoute(skuId, lockOrderId): void
├── findFromHistory(skuId): String
└── rebuildRouteFromDB(skuId): String
```

### 4.2 路由解析流程

```
扣减请求(skuId, lockOrderId可选)
    │
    ├── lockOrderId已指定 → 直接使用
    │
    └── lockOrderId未指定 → 路由解析
        │
        ├── 查询 inventory:active_lock:{skuId}
        │   ├── 命中 → 获取activeLockOrderId
        │   │   ├── 检查分桶索引(inventory:lock:{lockOrderId}:meta)有效性
        │   │   │   ├── 有效 → 使用该lockOrderId
        │   │   │   └── 已失效 → 进入历史路由兜底
        │   │   └── 检查total_remaining余量
        │   │       └── 余量为0 → 进入历史路由兜底
        │   │
        │   └── 未命中 → 查询DB重建路由
        │       ├── SELECT id FROM lock_inventory_order
        │       │   WHERE sku_id=? AND status='ACTIVE' ORDER BY created_at DESC LIMIT 1
        │       ├── 找到 → 重建路由缓存 + 使用该lockOrderId
        │       └── 未找到 → 降级走DB直接扣减
        │
        └── 历史路由兜底
            ├── 查询 inventory:active_lock_history:{skuId}
            ├── 按创建时间倒序遍历 (最多maxHistoryScan个)
            ├── 遍历时先检查total_remaining，余量为0跳过
            ├── 总耗时不超过historyScanTimeoutMs
            ├── 找到有效且有余量的lockOrder → 使用
            └── 全部无效或超时 → 降级走DB直接扣减
```

### 4.3 路由缓存数据结构

```
Redis Key: inventory:active_lock:{skuId}
Value: lockOrderId (当前活跃的锁库存单据ID)
TTL: 与锁库存单据过期时间一致

Redis Key: inventory:active_lock_history:{skuId}
Value: List[lockOrderId] (最近N个活跃的lockOrderId)
```

### 4.4 路由更新原子性保证

新lockOrder创建后（Redis分桶初始化完成、DB lq更新完成、lockOrder记录插入完成后），通过Redis SET原子更新路由缓存：

```java
public void updateActiveRoute(Long skuId, String lockOrderId) {
    redisTemplate.opsForValue().set(
        "inventory:active_lock:" + skuId,
        lockOrderId,
        lockExpireDuration,
        TimeUnit.MILLISECONDS
    );
    redisTemplate.opsForList().leftPush(
        "inventory:active_lock_history:" + skuId,
        lockOrderId
    );
}
```

## 5 Redis分桶扣减模块详细设计

### 5.1 类设计

```
BucketDeductService
├── deduct(orderId, skuId, quantity, lockOrderId): DeductResult
├── deductViaRedis(orderId, skuId, quantity, lockOrderId): DeductResult
└── deductViaDB(orderId, skuId, quantity): DeductResult

RedisBucketManager
├── executeDeductLua(bucketKey, totalRemainingKey, quantity): int
├── executeIncrRefundLua(bucketKey, totalRemainingKey, quantity): int
├── getTotalRemaining(lockOrderId): Long
└── isBucketMetaValid(lockOrderId): boolean
```

### 5.2 分桶策略

- **per-lockOrder分桶设计**：每次锁库存操作拥有独立的N个Redis分桶
- **Key格式**：`inventory:lock:{lockOrderId}:bucket:{n}`
- **分桶数量**：N可配置，默认16
- **分配策略**：均匀分配，每桶 count = actualLockQuantity / N
- **扣减路由**：随机选择 + 单桶耗尽fallover
- **分桶索引**：`inventory:lock:{lockOrderId}:meta`，存储分桶数量、skuId、各桶Key
- **总余量Key**：`inventory:lock:{lockOrderId}:total_remaining`

### 5.3 扣减主流程

```java
public DeductResult deduct(String orderId, Long skuId, int quantity, String lockOrderId) {
    DeductionDetail existing = deductionMapper.selectByOrderAndSku(orderId, skuId);
    if (existing != null) {
        redisBucketManager.executeIncrRefundLua(
            getBucketKey(existing.getLockOrderId(), existing.getBucketIndex()),
            getTotalRemainingKey(existing.getLockOrderId()),
            quantity
        );
        return DeductResult.success(existing.getId());
    }

    if (lockOrderId == null) {
        lockOrderId = activeLockRouter.resolveLockOrderId(skuId, null);
    }

    if (lockOrderId != null) {
        DeductResult result = deductViaRedis(orderId, skuId, quantity, lockOrderId);
        if (result.isSuccess()) {
            return result;
        }
    }

    return deductViaDB(orderId, skuId, quantity);
}
```

### 5.4 Redis分桶扣减详细流程

```java
private DeductResult deductViaRedis(String orderId, Long skuId, int quantity, String lockOrderId) {
    BucketMeta meta = redisBucketManager.getBucketMeta(lockOrderId);
    if (meta == null) {
        return DeductResult.fail("BUCKET_META_INVALID");
    }

    List<Integer> bucketIndices = generateShuffledIndices(meta.getBucketCount());
    int retryCount = Math.min(falloverRetryCount, bucketIndices.size());

    for (int i = 0; i < retryCount; i++) {
        int bucketIndex = bucketIndices.get(i);
        String bucketKey = "inventory:lock:" + lockOrderId + ":bucket:" + bucketIndex;
        String totalRemainingKey = "inventory:lock:" + lockOrderId + ":total_remaining";

        int luaResult = redisBucketManager.executeDeductLua(bucketKey, totalRemainingKey, quantity);
        if (luaResult == 1) {
            try {
                DeductionDetail detail = new DeductionDetail();
                detail.setId(IdGenerator.nextId());
                detail.setOrderId(orderId);
                detail.setSkuId(skuId);
                detail.setQuantity(quantity);
                detail.setDeductPath("MERGE_BUCKETS");
                detail.setBucketIndex(bucketIndex);
                detail.setStatus("PENDING");
                detail.setLockOrderId(lockOrderId);
                deductionMapper.insert(detail);
                return DeductResult.success(detail.getId());
            } catch (DuplicateKeyException e) {
                redisBucketManager.executeIncrRefundLua(bucketKey, totalRemainingKey, quantity);
                return DeductResult.success(existingId);
            } catch (Exception e) {
                redisBucketManager.executeIncrRefundLua(bucketKey, totalRemainingKey, quantity);
                return DeductResult.fail("DB_INSERT_FAILED");
            }
        }
    }

    return DeductResult.fail("ALL_BUCKETS_INSUFFICIENT");
}
```

### 5.5 DB降级扣减详细流程

```java
private DeductResult deductViaDB(String orderId, Long skuId, int quantity) {
    try {
        transactionTemplate.execute(status -> {
            int updated = inventoryMapper.deductDirect(skuId, quantity);
            if (updated == 0) {
                throw new InsufficientStockException();
            }
            DeductionDetail detail = new DeductionDetail();
            detail.setId(IdGenerator.nextId());
            detail.setOrderId(orderId);
            detail.setSkuId(skuId);
            detail.setQuantity(quantity);
            detail.setDeductPath("DIRECT_DB");
            detail.setBucketIndex(null);
            detail.setStatus("MERGED");
            detail.setLockOrderId(null);
            deductionMapper.insert(detail);
            return null;
        });
        return DeductResult.success(detailId);
    } catch (InsufficientStockException e) {
        return DeductResult.fail("INSUFFICIENT_STOCK");
    }
}
```

### 5.6 Lua脚本定义

#### 扣减Lua脚本

```lua
-- KEYS[1] = bucket key
-- KEYS[2] = total_remaining key
-- ARGV[1] = deduct quantity
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local total = tonumber(redis.call('GET', KEYS[2]) or '0')
local quantity = tonumber(ARGV[1])
if current >= quantity and total >= quantity then
    redis.call('DECRBY', KEYS[1], quantity)
    local remaining = redis.call('DECRBY', KEYS[2], quantity)
    if tonumber(remaining) <= 0 then
        return 2  -- success + 分桶耗尽，触发合并提交
    end
    return 1  -- success
else
    return 0  -- insufficient
end
```

#### 初始化分桶Lua脚本

```lua
-- KEYS[1..N] = bucket keys
-- KEYS[N+1] = meta key
-- KEYS[N+2] = total_remaining key
-- ARGV[1..N] = bucket initial values
-- ARGV[N+1] = meta value (JSON)
-- ARGV[N+2] = total_remaining initial value
for i = 1, #KEYS - 2 do
    redis.call('SET', KEYS[i], ARGV[i])
end
redis.call('SET', KEYS[#KEYS - 1], ARGV[#KEYS - 1])
redis.call('SET', KEYS[#KEYS], ARGV[#KEYS])
return #KEYS - 2
```

#### 清理分桶Lua脚本

```lua
-- KEYS[1..N] = bucket keys
-- KEYS[N+1] = meta key
-- KEYS[N+2] = total_remaining key
for i = 1, #KEYS do
    redis.call('DEL', KEYS[i])
end
return 1
```

#### INCR回补Lua脚本

```lua
-- KEYS[1] = meta key (inventory:lock:{lockOrderId}:meta)
-- KEYS[2] = bucket key (inventory:lock:{lockOrderId}:bucket:{n})
-- KEYS[3] = total_remaining key
-- ARGV[1] = refund quantity
local metaExists = redis.call('EXISTS', KEYS[1])
if tonumber(metaExists) == 1 then
    redis.call('INCRBY', KEYS[2], ARGV[1])
    redis.call('INCRBY', KEYS[3], ARGV[1])
    return 1  -- INCR回补成功
else
    return 0  -- meta已失效，跳过INCR
end
```

## 6 合并提交模块详细设计

### 6.1 类设计

```
MergeScheduler
├── triggerMerge(lockOrderId): MergeResult
├── scheduleMerge(): void
└── checkBucketExhausted(): void

DetailAggregator
├── markPendingAsMerged(lockOrderId, batchId): int
├── calculateNetDeduction(batchId): int
└── updateInventoryAndStatus(skuId, netDeduction, lockOrderId, currentLockQuantity): void
```

### 6.2 合并触发策略

| 触发方式 | 条件 | 优先级 |
|----------|------|--------|
| 延迟触发 | 合并窗口期到期（默认1秒） | 常规 |
| 分桶耗尽触发 | 某lockOrder所有分桶余量为0 | 立即 |
| 活跃度衰减触发 | 扣减QPS低于阈值（默认100/s） | 提前 |
| 手动触发 | 运维接口调用 | 立即 |

### 6.3 合并提交流程（先标记后计算）

```
┌──────────────────────────────────────────────────────────┐
│ 1. 获取分布式锁 (Redisson RLock, key=merge:{lockOrderId})  │
│    → 获取失败: 说明已有合并任务在执行，直接返回              │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 2. 失效分桶索引缓存 (扣减屏障)                              │
│    DEL inventory:lock:{lockOrderId}:meta                   │
│    → 性能优化：减少穿透到事务内的请求数量                     │
│    → 非正确性必要条件                                      │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 3. 分配merge_batch_id (前缀MERGE-{uuid})                   │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 4. @Transactional 事务内执行                               │
│                                                           │
│ 4a. UPDATE deduction_detail                               │
│     SET status='MERGED', merge_batch_id=#{batchId}        │
│     WHERE lock_order_id=#{lockOrderId}                    │
│       AND status='PENDING'                                │
│       AND merge_batch_id IS NULL                          │
│     → 获取行锁，阻止并发CANCEL                             │
│     → 原子标记所有PENDING为MERGED                          │
│                                                           │
│ 4b. SELECT SUM(quantity) AS net_deduction                 │
│     FROM deduction_detail                                 │
│     WHERE merge_batch_id=#{batchId}                       │
│     → 从实际标记的明细计算净扣减值                          │
│                                                           │
│ 4c. SELECT lock_quantity AS currentLockQuantity           │
│     FROM lock_inventory_order                             │
│     WHERE id=#{lockOrderId}                               │
│     → 获取当前lockOrder的锁定量                            │
│                                                           │
│ 4d. UPDATE inventory                                      │
│     SET sq = sq - #{net_deduction},                       │
│         wq = wq + #{net_deduction},                       │
│         lq = lq - #{currentLockQuantity}                  │
│     WHERE id=#{skuId}                                     │
│       AND sq >= #{net_deduction}                          │
│       AND lq >= #{currentLockQuantity}                    │
│     → lq减量更新，支持多lockOrder并存                      │
│     → WHERE sq >= net_deduction 最终防线                  │
│     → WHERE lq >= currentLockQuantity 防止lq变负          │
│     → 影响行数为0则事务回滚 + 告警                         │
│                                                           │
│ 4e. UPDATE lock_inventory_order                           │
│     SET status='ARCHIVED' WHERE id=#{lockOrderId}         │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 5. 清零/删除Redis分桶                                      │
│    DEL inventory:lock:{lockOrderId}:bucket:0..N-1          │
│    DEL inventory:lock:{lockOrderId}:meta                   │
│    DEL inventory:lock:{lockOrderId}:total_remaining        │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 6. 更新merge_completed标记                                 │
│    UPDATE lock_inventory_order                            │
│    SET merge_completed = true WHERE id=#{lockOrderId}      │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│ 7. 释放分布式锁                                            │
└──────────────────────────────────────────────────────────┘
```

### 6.4 合并提交伪代码

```java
public MergeResult triggerMerge(String lockOrderId) {
    RLock lock = redissonClient.getLock("merge:" + lockOrderId);
    boolean acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
    if (!acquired) {
        return MergeResult.skip("MERGE_IN_PROGRESS");
    }

    try {
        BucketMeta meta = redisBucketManager.getBucketMeta(lockOrderId);
        if (meta != null) {
            redisTemplate.delete("inventory:lock:" + lockOrderId + ":meta");
        }

        String batchId = "MERGE-" + UUID.randomUUID().toString();

        transactionTemplate.execute(status -> {
            int marked = deductionMapper.markPendingAsMerged(lockOrderId, batchId);
            if (marked == 0) {
                return null;
            }

            Integer netDeduction = deductionMapper.calculateNetDeduction(batchId);
            if (netDeduction == null || netDeduction == 0) {
                return null;
            }

            LockInventoryOrder lockOrder = lockOrderMapper.selectById(lockOrderId);
            int currentLockQuantity = lockOrder.getLockQuantity();
            Long skuId = lockOrder.getSkuId();

            int updated = inventoryMapper.mergeCommit(skuId, netDeduction, currentLockQuantity);
            if (updated == 0) {
                throw new MergeCommitFailedException("SQ_OR_LQ_INSUFFICIENT");
            }

            lockOrderMapper.updateStatusToArchived(lockOrderId);
            return null;
        });

        lock.unlock();
        lock = null;

        redisBucketManager.cleanupBuckets(lockOrderId, bucketCount);
        lockOrderMapper.updateMergeCompleted(lockOrderId);

    } finally {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    return MergeResult.success();
}
```

## 7 补偿合并模块详细设计

### 7.1 类设计

```
CompensateService
├── compensateOrphanDetails(): void
├── compensateSingleLockOrder(lockOrderId): void
└── recoverCrashIncompleteMerge(): void
```

### 7.2 孤立PENDING明细补偿

合并提交事务提交后，极端时序下可能产生PENDING明细但其父lockOrder已ARCHIVED：

```java
@Scheduled(fixedDelayString = "${store.compensate.interval-ms:5000}")
public void compensateOrphanDetails() {
    List<String> orphanLockOrderIds = deductionMapper.selectOrphanLockOrderIds();
    for (String lockOrderId : orphanLockOrderIds) {
        compensateSingleLockOrder(lockOrderId);
    }
}

public void compensateSingleLockOrder(String lockOrderId) {
    RLock lock = redissonClient.getLock("compensate:" + lockOrderId);
    boolean acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
    if (!acquired) {
        return;
    }

    try {
        String compensateBatchId = "COMP-" + UUID.randomUUID().toString();

        transactionTemplate.execute(status -> {
            int marked = deductionMapper.markPendingAsMerged(lockOrderId, compensateBatchId);
            if (marked == 0) {
                return null;
            }

            Integer netDeduction = deductionMapper.calculateNetDeduction(compensateBatchId);
            if (netDeduction == null || netDeduction == 0) {
                return null;
            }

            LockInventoryOrder lockOrder = lockOrderMapper.selectById(lockOrderId);
            Long skuId = lockOrder.getSkuId();

            int updated = inventoryMapper.compensateMerge(skuId, netDeduction);
            if (updated == 0) {
                throw new CompensateMergeFailedException("SQ_INSUFFICIENT");
            }

            return null;
        });
    } finally {
        lock.unlock();
    }
}
```

### 7.3 崩溃恢复

应用启动时扫描未完成的合并提交记录：

```java
@PostConstruct
public void recoverCrashIncompleteMerge() {
    List<LockInventoryOrder> incompleteOrders =
        lockOrderMapper.selectArchivedIncomplete();

    for (LockInventoryOrder order : incompleteOrders) {
        redisBucketManager.cleanupBuckets(order.getId(), bucketCount);
        lockOrderMapper.updateMergeCompleted(order.getId());
    }
}
```

## 8 回补管理模块详细设计

### 8.1 类设计

```
RefundService
├── cancel(orderId, skuId): void
├── refund(orderId, skuId, refundQuantity): void
├── confirmPayment(orderId, skuId): void
└── handlePendingCancel(detail): void
```

### 8.2 明细状态机详细转换

```
【合并下单明细路径】(deduct_path=MERGE_BUCKETS)

PENDING ──合并提交──→ MERGED ──付款确认──→ OCCUPIED ──退款──→ REFUNDED
   │                     │
   └──取消(付款前)──→ CANCELLED
                         │
                         └──取消(付款前)──→ CANCELLED

【普通下单明细路径】(deduct_path=DIRECT_DB)

MERGED ──付款确认──→ OCCUPIED ──退款──→ REFUNDED
   │
   └──取消(付款前)──→ CANCELLED
```

### 8.3 状态转换详细操作

| 当前状态 | 触发事件 | 目标状态 | DB库存操作 | Redis操作 |
|----------|----------|----------|-----------|-----------|
| PENDING | 合并提交 | MERGED | sq减少, wq增加, lq减量 | 分桶清除(合并提交统一处理) |
| PENDING | 取消(付款前) | CANCELLED | 无需DB库存操作 | 条件INCR回补: 检查meta有效性, 有效则回补 |
| MERGED | 付款确认 | OCCUPIED | wq减少, oq增加 | 无 |
| MERGED | 取消(付款前) | CANCELLED | wq减少, sq增加(回补) | 无 |
| OCCUPIED | 退款 | REFUNDED | oq减少, sq增加(回补) | 无 |

### 8.4 PENDING取消条件INCR回补

```java
private void handlePendingCancel(DeductionDetail detail) {
    String metaKey = "inventory:lock:" + detail.getLockOrderId() + ":meta";
    String bucketKey = "inventory:lock:" + detail.getLockOrderId()
        + ":bucket:" + detail.getBucketIndex();
    String totalRemainingKey = "inventory:lock:" + detail.getLockOrderId()
        + ":total_remaining";
    redisBucketManager.executeIncrRefundLua(
        metaKey, bucketKey, totalRemainingKey, detail.getQuantity());
}
```

### 8.5 MERGED取消/退款流程

```java
public void cancel(String orderId, Long skuId) {
    DeductionDetail detail = deductionMapper.selectByOrderAndSku(orderId, skuId);

    if (detail.getStatus().equals("PENDING")) {
        deductionMapper.updateStatus(detail.getId(), "CANCELLED");
        handlePendingCancel(detail);
    } else if (detail.getStatus().equals("MERGED")) {
        transactionTemplate.execute(status -> {
            RefundDetail refund = new RefundDetail();
            refund.setId(IdGenerator.nextId());
            refund.setSkuId(skuId);
            refund.setRefundQuantity(detail.getQuantity());
            refund.setDeductPath(detail.getDeductPath());
            refund.setStatus("MERGED");
            refund.setOrderId(orderId);
            refund.setRefDetailId(detail.getId());
            refundMapper.insert(refund);

            deductionMapper.updateStatus(detail.getId(), "CANCELLED");
            inventoryMapper.refundFromWq(skuId, detail.getQuantity());
            return null;
        });
    }
}

public void refund(String orderId, Long skuId, int refundQuantity) {
    DeductionDetail detail = deductionMapper.selectByOrderAndSku(orderId, skuId);

    if (detail.getStatus().equals("OCCUPIED")) {
        transactionTemplate.execute(status -> {
            RefundDetail refund = new RefundDetail();
            refund.setId(IdGenerator.nextId());
            refund.setSkuId(skuId);
            refund.setRefundQuantity(refundQuantity);
            refund.setDeductPath(detail.getDeductPath());
            refund.setStatus("MERGED");
            refund.setOrderId(orderId);
            refund.setRefDetailId(detail.getId());
            refundMapper.insert(refund);

            deductionMapper.updateStatus(detail.getId(), "REFUNDED");
            inventoryMapper.refundFromOq(skuId, refundQuantity);
            return null;
        });
    }
}
```

## 9 紧急降级模块详细设计

### 9.1 类设计

```
EmergencyService
├── emergencyUnlock(skuId): void
├── checkRedisHealth(): boolean
└── triggerEmergencyMerge(skuId): void
```

### 9.2 紧急降级流程

```
Redis连续超时次数 ≥ failThreshold (默认5次)
    │
    ├── 自动触发紧急合并提交
    │   → 对所有ACTIVE lockOrder触发合并提交
    │   → 释放lq使DB降级路径可用
    │
    └── 人工触发紧急解锁 (管理接口)
        → emergencyUnlock(skuId)
        → 对所有ACTIVE lockOrder触发紧急合并提交
        → 或直接 UPDATE inventory SET lq = 0 WHERE id = #{skuId}
        → SET lq=0后必须同步UPDATE lock_inventory_order SET status='ARCHIVED'
           WHERE sku_id = #{skuId} AND status = 'ACTIVE'
        → 设置紧急降级开关 inventory:emergency_degrade:{skuId} = true, TTL=30s
        → 降级开关存在期间扣减请求跳过Redis路径，直接走DB降级
```

### 9.3 Redis健康检测

```java
private final AtomicInteger failCount = new AtomicInteger(0);

public boolean checkRedisHealth() {
    try {
        redisTemplate.ping();
        failCount.set(0);
        return true;
    } catch (Exception e) {
        int count = failCount.incrementAndGet();
        if (count >= redisFailThreshold) {
            triggerEmergencyMergeForAll();
        }
        return false;
    }
}
```

## 10 锁超时释放模块详细设计

### 10.1 类设计

```
LockExpireCleaner
└── checkExpired(): void
```

### 10.2 超时释放流程

```java
@Scheduled(fixedDelayString = "${store.lock-expire.check-interval-ms:30000}")
public void checkExpired() {
    List<LockInventoryOrder> expiredOrders =
        lockOrderMapper.selectExpiredActive();

    for (LockInventoryOrder order : expiredOrders) {
        try {
            mergeScheduler.triggerMerge(order.getId());
            metricsCollector.incrementLockExpireCount();
        } catch (Exception e) {
            log.error("Lock expire merge failed, lockOrderId={}", order.getId(), e);
        }
    }
}
```

## 11 可观测性模块详细设计

### 11.1 监控指标定义

| 指标名 | 类型 | 说明 | 采集点 |
|--------|------|------|--------|
| store.deduct.redis.success.count | Counter | Redis分桶扣减成功次数 | BucketDeductService |
| store.deduct.redis.fallover.count | Counter | 单桶耗竭fallover次数 | BucketDeductService |
| store.deduct.redis.degrade.count | Counter | 降级到DB直接扣减次数 | BucketDeductService |
| store.deduct.redis.degrade.ratio | Gauge | 降级DB扣减比例 | BucketDeductService |
| store.merge.delay.ms | Timer | 合并提交延迟 | MergeScheduler |
| store.merge.batch.size | DistributionSummary | 每次合并处理的明细数量 | MergeScheduler |
| store.lock.utilization | Gauge | 锁库存利用率 | LockService |
| store.lock.expire.count | Counter | 锁库存超时自动释放次数 | LockExpireCleaner |
| store.redis.compensate.count | Counter | Redis回补次数 | BucketDeductService |
| store.reconcile.mismatch.count | Counter | 对账不一致次数 | ReconcileTask |
| store.auto-lock.create.count | Counter | 自动锁库存创建次数 | AutoLockService |
| store.auto-lock.fail.count | Counter | 自动锁库存创建失败次数 | AutoLockService |
| store.active-lock.route.hit.count | Counter | 路由缓存命中次数 | ActiveLockRouter |
| store.active-lock.route.miss.count | Counter | 路由缓存未命中次数 | ActiveLockRouter |
| store.compensate.merge.count | Counter | 补偿合并执行次数 | CompensateService |
| store.compensate.merge.fail.count | Counter | 补偿合并失败次数 | CompensateService |
| store.emergency.unlock.count | Counter | 紧急解锁执行次数 | EmergencyService |
| store.merge.crash.recover.count | Counter | 崩溃恢复次数 | CompensateService |

### 11.2 指标采集实现

```java
@Component
public class MetricsCollector {
    private final Counter redisSuccessCounter;
    private final Counter redisFalloverCounter;
    private final Counter redisDegradeCounter;
    private final Timer mergeDelayTimer;

    public MetricsCollector(MeterRegistry registry) {
        this.redisSuccessCounter = Counter.builder("store.deduct.redis.success.count")
            .description("Redis bucket deduction success count")
            .register(registry);
        this.redisFalloverCounter = Counter.builder("store.deduct.redis.fallover.count")
            .description("Redis bucket fallover count")
            .register(registry);
        this.redisDegradeCounter = Counter.builder("store.deduct.redis.degrade.count")
            .description("Redis degradation to DB count")
            .register(registry);
        this.mergeDelayTimer = Timer.builder("store.merge.delay.ms")
            .description("Merge commit delay")
            .register(registry);
    }
}
```

## 12 并发控制详细设计

### 12.1 并发场景与控制策略

| 并发场景 | 控制策略 | 机制 |
|----------|----------|------|
| 同一SKU并发锁库存 | SQL行锁 | WHERE sq - lq >= lockQuantity |
| 同一SKU并发扣减(Redis) | Lua原子操作 | 桶计数器原子DECR + total_remaining检查 |
| 同一SKU并发扣减(DB降级) | SQL行锁 | WHERE sq - lq >= quantity |
| 同一lockOrder并发合并提交 | 分布式锁 | RLock key=merge:{lockOrderId} |
| PENDING取消与合并提交竞态 | 行锁 + 先标记后计算 | UPDATE获取行锁阻止CANCEL |
| 同一订单重复扣减 | 唯一索引 | uk_order_sku (order_id, sku_id) |
| 同一幂等键重复锁库存 | 唯一索引 | uk_idempotent_key |
| 多lockOrder并存合并 | lq减量更新 + 非负约束 | lq = lq - #{currentLockQuantity} WHERE lq >= #{currentLockQuantity} |
| 二次合并触发 | Step 4a影响0行跳过 | 幂等保障，避免lq变负 |

### 12.2 PENDING取消与合并提交竞态处理

```
时序1: CANCEL在合并提交事务前完成
  CANCEL → 检查状态=PENDING → 更新为CANCELLED → 条件INCR回补Redis
  合并提交 → WHERE status='PENDING' → 不包含已CANCELLED的明细 → 正确

时序2: CANCEL在合并提交事务后执行
  合并提交 → UPDATE获取行锁 → 标记PENDING为MERGED → 事务提交
  CANCEL → 检查状态=MERGED → 走MERGED取消路径 → wq回补sq → 正确

时序3: CANCEL与合并提交并发
  合并提交 → UPDATE获取行锁 → CANCEL被阻塞
  合并提交事务提交 → CANCEL获取行锁 → 检查状态=MERGED → 走MERGED取消路径 → 正确
```

## 13 异常场景处理矩阵

| 异常场景 | 处理策略 | 影响范围 |
|----------|----------|----------|
| Redis Lua扣减成功，DB明细插入失败 | INCR回补Redis分桶+total_remaining | 单次扣减 |
| Redis扣减超时 | 当作失败，走DB降级 | 单次扣减 |
| 单桶余量不足 | fallover到其他桶重试(最多3次) | 单次扣减 |
| 所有桶不足 | 降级走DB直接扣减 | 单次扣减 |
| DB降级扣减sq-lq不足 | 返回INSUFFICIENT_STOCK | 单次扣减 |
| PENDING取消(合并提交前) | 条件INCR回补Redis | 单次扣减 |
| 合并提交DB更新失败 | 事务回滚+重试+告警 | 单个lockOrder |
| 合并提交sq不足 | 事务回滚+告警+人工处理 | 单个lockOrder |
| 合并期间新扣减请求 | 扣减屏障拦截+降级DB | 穿透请求 |
| DB锁库存成功Redis初始化失败 | 先Redis后DB策略+Lua原子初始化 | 单次锁库存 |
| DB锁库存事务失败 | DB事务回滚+Lua原子清理Redis | 单次锁库存 |
| 锁库存超时重试 | 幂等键去重 | 单次锁库存 |
| 合并任务重复触发 | 分布式锁+merge_batch_id幂等 | 单个lockOrder |
| 合并提交后应用崩溃 | 启动时补偿清理Redis分桶 | 崩溃前lockOrder |
| Redis全锁定+不可用 | 紧急解锁接口+预留DB降级额度 | 全局 |
| 扣减明细重复插入 | 唯一索引冲突+INCR回补Redis | 单次扣减 |
| 孤立PENDING明细 | 补偿合并扫描+分布式锁 | 单个lockOrder |
