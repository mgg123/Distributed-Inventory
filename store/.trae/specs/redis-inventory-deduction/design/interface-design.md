# 基于Redis分布式强一致库存扣减系统 — 接口设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 系统名称 | 基于Redis分布式强一致库存扣减系统 |
| 文档版本 | V1.0 |
| 编写日期 | 2026-05-01 |
| 文档状态 | 初稿 |

## 1 引言

### 1.1 编写目的

本文档为"基于Redis分布式强一致库存扣减系统"的接口设计文档，详细描述系统对外暴露的所有REST API接口、内部服务间调用接口、Redis操作接口的请求/响应格式、错误码定义、幂等策略和调用约束，为前后端联调和系统集成提供标准规范。

### 1.2 接口设计原则

| 原则 | 说明 |
|------|------|
| RESTful风格 | 资源导向的URL设计，标准HTTP方法语义 |
| 幂等保障 | 写操作均支持幂等，通过业务唯一键或请求ID去重 |
| 统一响应格式 | 所有接口采用统一的响应结构体 |
| 错误码标准化 | 错误码分层设计，便于问题定位 |
| 向后兼容 | 接口变更保持向后兼容，通过版本号管理 |

### 1.3 通用约定

#### 请求头

| Header | 必填 | 说明 |
|--------|------|------|
| Content-Type | 是 | application/json |
| X-Request-Id | 否 | 请求追踪ID，不传则自动生成 |
| X-Idempotent-Key | 否 | 幂等键，锁库存接口必填 |

#### 统一响应格式

```json
{
    "code": 0,
    "message": "success",
    "data": {},
    "traceId": "abc123def456"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | Integer | 响应码，0表示成功，非0表示失败 |
| message | String | 响应消息 |
| data | Object | 响应数据，失败时为null |
| traceId | String | 追踪ID |

#### 分页响应格式

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "list": [],
        "total": 100,
        "page": 1,
        "pageSize": 20
    },
    "traceId": "abc123def456"
}
```

## 2 错误码定义

### 2.1 错误码规范

错误码格式：`{系统码}{模块码}{错误序号}`

- 系统码：1 (库存系统)
- 模块码：01-库存操作, 02-锁库存, 03-合并提交, 04-路由, 99-系统

### 2.2 错误码列表

| 错误码 | 错误名 | HTTP状态码 | 说明 |
|--------|--------|-----------|------|
| 0 | SUCCESS | 200 | 成功 |
| 101001 | INSUFFICIENT_STOCK | 409 | 库存不足 |
| 101002 | STOCK_NOT_FOUND | 404 | 库存记录不存在 |
| 101003 | DEDUCTION_DUPLICATE | 200 | 扣减幂等命中（视为成功） |
| 102001 | LOCK_QUANTITY_EXCEEDED | 409 | 可用额度不足，无法锁库存 |
| 102002 | LOCK_ORDER_NOT_FOUND | 404 | 锁库存单据不存在 |
| 102003 | LOCK_ORDER_NOT_ACTIVE | 409 | 锁库存单据非活跃状态 |
| 102004 | LOCK_IDEMPOTENT_CONFLICT | 200 | 锁库存幂等命中（视为成功） |
| 103001 | MERGE_IN_PROGRESS | 409 | 合并提交正在进行中 |
| 103002 | MERGE_NO_PENDING | 200 | 无待合并明细 |
| 103003 | MERGE_SQ_INSUFFICIENT | 500 | 合并提交sq不足（告警） |
| 104001 | ROUTE_NOT_FOUND | 404 | 活跃路由不存在 |
| 104002 | ROUTE_BUCKET_INVALID | 409 | 分桶索引已失效 |
| 199001 | PARAM_INVALID | 400 | 参数校验失败 |
| 199002 | INTERNAL_ERROR | 500 | 系统内部错误 |
| 199003 | REDIS_UNAVAILABLE | 503 | Redis服务不可用 |

## 3 外部API接口

### 3.1 锁库存接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/lock |
| 接口描述 | 将DB行库存从sq预锁定到lq，初始化Redis分桶，创建锁库存单据 |
| 调用方 | 交易系统/运营后台 |
| 幂等策略 | 通过idempotentKey唯一索引保证幂等 |

#### 请求参数

```json
{
    "skuId": 10001,
    "lockQuantity": 10000,
    "idempotentKey": "lock-20260501-10001-001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| skuId | Long | 是 | 商品ID/SKU |
| lockQuantity | Integer | 是 | 锁定数量，必须大于0 |
| idempotentKey | String | 是 | 幂等键，全局唯一，最长128字符 |

#### 响应参数

**成功响应：**

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "lockOrderId": "1234567890",
        "skuId": 10001,
        "actualLockQuantity": 10000,
        "bucketCount": 16,
        "expireTime": "2026-05-01T13:00:00"
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| lockOrderId | String | 锁库存单据ID，后续扣减时关联使用 |
| skuId | Long | 商品ID |
| actualLockQuantity | Integer | 实际锁定量（可能因部分锁定而小于请求量） |
| bucketCount | Integer | Redis分桶数量 |
| expireTime | String | 过期时间(ISO 8601) |

**部分锁定响应：**

```json
{
    "code": 0,
    "message": "partial lock applied",
    "data": {
        "lockOrderId": "1234567890",
        "skuId": 10001,
        "actualLockQuantity": 500,
        "bucketCount": 16,
        "expireTime": "2026-05-01T13:00:00"
    }
}
```

**失败响应：**

```json
{
    "code": 102001,
    "message": "LOCK_QUANTITY_EXCEEDED: available quantity is below minimum lock quantity",
    "data": null,
    "traceId": "abc123"
}
```

#### 业务规则

| 规则 | 说明 |
|------|------|
| 部分锁定 | 当sq-lq < lockQuantity但 >= min-lock-quantity时，自动调整为sq-lq |
| 最小锁定量 | 可用额度低于store.auto-lock.min-lock-quantity(默认100)时返回错误 |
| 严格时序 | Redis初始化 → DB事务 → 路由更新，任何前置步骤失败不继续 |
| 预留额度 | 自动锁库存时预留reserve-ratio(默认10%)给DB降级路径 |

---

### 3.2 释放锁库存接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/lock/{lockOrderId}/release |
| 接口描述 | 主动释放锁库存，触发合并提交流程，将已卖出部分从sq转移到wq，lq减去lockQuantity |
| 调用方 | 交易系统/运营后台 |
| 幂等策略 | 合并提交的分布式锁+merge_batch_id保证幂等 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lockOrderId | String | 是 | 锁库存单据ID（路径参数） |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "lockOrderId": "1234567890",
        "netDeduction": 300,
        "releasedQuantity": 700,
        "status": "ARCHIVED"
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| lockOrderId | String | 锁库存单据ID |
| netDeduction | Integer | 实际卖出数量（sq减少量） |
| releasedQuantity | Integer | 释放的未卖出数量（自然保留在sq中） |
| status | String | 单据状态，释放后为ARCHIVED |

---

### 3.3 扣减库存接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/deduct |
| 接口描述 | 扣减库存，优先走Redis分桶预扣减，Redis不可用时降级走DB直接扣减 |
| 调用方 | 交易/订单系统 |
| 幂等策略 | (order_id, sku_id)唯一索引保证幂等 |

#### 请求参数

```json
{
    "orderId": "ORDER-20260501-001",
    "skuId": 10001,
    "quantity": 10,
    "lockOrderId": null
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderId | String | 是 | 订单ID，全局唯一，用于幂等和回补关联 |
| skuId | Long | 是 | 商品ID/SKU |
| quantity | Integer | 是 | 扣减数量，必须大于0 |
| lockOrderId | String | 否 | 锁库存单据ID，不传则通过路由缓存自动获取 |

#### 响应参数

**Redis分桶扣减成功：**

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "detailId": "9876543210",
        "orderId": "ORDER-20260501-001",
        "skuId": 10001,
        "quantity": 10,
        "deductPath": "MERGE_BUCKETS",
        "status": "PENDING",
        "lockOrderId": "1234567890",
        "bucketIndex": 7
    }
}
```

**DB降级扣减成功：**

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "detailId": "9876543211",
        "orderId": "ORDER-20260501-002",
        "skuId": 10001,
        "quantity": 10,
        "deductPath": "DIRECT_DB",
        "status": "MERGED",
        "lockOrderId": null,
        "bucketIndex": null
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| detailId | String | 扣减明细ID |
| orderId | String | 订单ID |
| skuId | Long | 商品ID |
| quantity | Integer | 扣减数量 |
| deductPath | String | 扣减路径：MERGE_BUCKETS/DIRECT_DB |
| status | String | 明细状态：PENDING(合并下单)/MERGED(普通下单) |
| lockOrderId | String | 关联锁库存单据ID，DIRECT_DB路径为null |
| bucketIndex | Integer | 桶编号，DIRECT_DB路径为null |

**库存不足响应：**

```json
{
    "code": 101001,
    "message": "INSUFFICIENT_STOCK: available stock is insufficient",
    "data": null,
    "traceId": "abc123"
}
```

**幂等命中响应：**

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "detailId": "9876543210",
        "orderId": "ORDER-20260501-001",
        "skuId": 10001,
        "quantity": 10,
        "deductPath": "MERGE_BUCKETS",
        "status": "PENDING",
        "lockOrderId": "1234567890",
        "bucketIndex": 7
    }
}
```

#### 业务规则

| 规则 | 说明 |
|------|------|
| 路由解析 | 未指定lockOrderId时，通过active_lock路由缓存自动获取 |
| 扣减屏障 | 分桶索引已失效时，尝试历史路由兜底，全部无效则降级DB |
| fallover | 单桶不足时自动重试其他桶，最多3次 |
| DB降级 | Redis全部桶不足或异常时，降级走DB直接扣减 |
| 幂等 | (order_id, sku_id)唯一索引，重复请求返回已有明细 |

---

### 3.4 查询库存接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | GET /api/v1/inventory/{skuId} |
| 接口描述 | 查询SKU维度的库存信息 |
| 调用方 | 交易系统/前端展示 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| skuId | Long | 是 | 商品ID/SKU（路径参数） |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "skuId": 10001,
        "sq": 8500,
        "wq": 1000,
        "oq": 500,
        "lq": 2000,
        "availableQuantity": 6500,
        "activeLockOrders": [
            {
                "lockOrderId": "1234567890",
                "lockQuantity": 10000,
                "status": "ACTIVE",
                "totalRemaining": 7000,
                "expireTime": "2026-05-01T13:00:00"
            },
            {
                "lockOrderId": "1234567891",
                "lockQuantity": 10000,
                "status": "ACTIVE",
                "totalRemaining": 9500,
                "expireTime": "2026-05-01T13:05:00"
            }
        ]
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| skuId | Long | 商品ID |
| sq | Integer | 可售库存 |
| wq | Integer | 预扣库存 |
| oq | Integer | 占用库存 |
| lq | Integer | 预锁库存 |
| availableQuantity | Integer | 可用额度 = sq - lq |
| activeLockOrders | Array | 活跃锁库存单据列表 |

---

### 3.5 手动触发合并接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/merge |
| 接口描述 | 手动触发指定lockOrder的合并提交（运维接口） |
| 调用方 | 运维/管理后台 |
| 幂等策略 | 分布式锁+merge_batch_id保证幂等 |

#### 请求参数

```json
{
    "lockOrderId": "1234567890"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lockOrderId | String | 是 | 锁库存单据ID |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "lockOrderId": "1234567890",
        "mergeBatchId": "MERGE-uuid-1234",
        "mergedCount": 100,
        "netDeduction": 500,
        "status": "ARCHIVED"
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| lockOrderId | String | 锁库存单据ID |
| mergeBatchId | String | 合并批次ID |
| mergedCount | Integer | 合并的明细数量 |
| netDeduction | Integer | 净扣减数量 |
| status | String | 合并后单据状态 |

---

### 3.6 付款确认接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/confirm |
| 接口描述 | 确认付款，将明细状态从MERGED更新为OCCUPIED，DB库存wq转移到oq |
| 调用方 | 支付系统回调 |
| 幂等策略 | 状态检查，已OCCUPIED则直接返回成功 |

#### 请求参数

```json
{
    "orderId": "ORDER-20260501-001",
    "skuId": 10001
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderId | String | 是 | 订单ID |
| skuId | Long | 是 | 商品ID |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "detailId": "9876543210",
        "orderId": "ORDER-20260501-001",
        "skuId": 10001,
        "previousStatus": "MERGED",
        "currentStatus": "OCCUPIED"
    }
}
```

---

### 3.7 取消订单接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/cancel |
| 接口描述 | 取消订单，根据明细当前状态执行对应回补操作 |
| 调用方 | 交易/订单系统 |
| 幂等策略 | 状态检查，已CANCELLED则直接返回成功 |

#### 请求参数

```json
{
    "orderId": "ORDER-20260501-001",
    "skuId": 10001
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderId | String | 是 | 订单ID |
| skuId | Long | 是 | 商品ID |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "detailId": "9876543210",
        "orderId": "ORDER-20260501-001",
        "skuId": 10001,
        "previousStatus": "MERGED",
        "currentStatus": "CANCELLED",
        "refundDetailId": "REF-1111111111",
        "refundQuantity": 10
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| detailId | String | 扣减明细ID |
| orderId | String | 订单ID |
| skuId | Long | 商品ID |
| previousStatus | String | 变更前状态 |
| currentStatus | String | 变更后状态 |
| refundDetailId | String | 回补明细ID（PENDING取消时为null） |
| refundQuantity | Integer | 回补数量（PENDING取消时为0） |

#### 取消逻辑分支

| 原明细状态 | 目标状态 | DB库存操作 | Redis操作 | 是否创建refund_detail |
|-----------|----------|-----------|-----------|---------------------|
| PENDING | CANCELLED | 无 | 条件INCR回补 | 否 |
| MERGED | CANCELLED | wq减少, sq增加 | 无 | 是 |
| OCCUPIED | 不可取消 | - | - | - |

---

### 3.8 退款接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/refund |
| 接口描述 | 退款，将明细状态从OCCUPIED更新为REFUNDED，DB库存oq回补到sq |
| 调用方 | 支付/售后系统 |
| 幂等策略 | 回补明细ID作为主键天然幂等 |

#### 请求参数

```json
{
    "orderId": "ORDER-20260501-001",
    "skuId": 10001,
    "refundQuantity": 10,
    "refundId": "REFUND-20260501-001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderId | String | 是 | 订单ID |
| skuId | Long | 是 | 商品ID |
| refundQuantity | Integer | 是 | 退款数量，支持部分退款，必须大于0且不超过原扣减数量 |
| refundId | String | 是 | 退款单号，作为回补明细ID，天然幂等 |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "refundDetailId": "REFUND-20260501-001",
        "orderId": "ORDER-20260501-001",
        "skuId": 10001,
        "refundQuantity": 10,
        "previousStatus": "OCCUPIED",
        "currentStatus": "REFUNDED"
    }
}
```

---

### 3.9 紧急解锁接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | POST /api/v1/inventory/emergency-unlock |
| 接口描述 | 紧急解锁，当Redis不可用且sq-lq=0时，释放lq使DB降级路径可用 |
| 调用方 | 运维/管理后台 |
| 幂等策略 | 幂等，重复调用安全 |

#### 请求参数

```json
{
    "skuId": 10001,
    "force": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| skuId | Long | 是 | 商品ID |
| force | Boolean | 否 | 是否强制解锁（直接SET lq=0），默认false |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "skuId": 10001,
        "releasedLockOrders": ["1234567890", "1234567891"],
        "previousLq": 20000,
        "currentLq": 0
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| skuId | Long | 商品ID |
| releasedLockOrders | Array | 已释放的lockOrder列表 |
| previousLq | Integer | 释放前lq值 |
| currentLq | Integer | 释放后lq值 |

---

### 3.10 查询扣减明细接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | GET /api/v1/inventory/deduction/{orderId} |
| 接口描述 | 查询指定订单的扣减明细 |
| 调用方 | 交易/订单系统 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderId | String | 是 | 订单ID（路径参数） |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "detailId": "9876543210",
        "orderId": "ORDER-20260501-001",
        "skuId": 10001,
        "quantity": 10,
        "deductPath": "MERGE_BUCKETS",
        "status": "MERGED",
        "lockOrderId": "1234567890",
        "bucketIndex": 7,
        "mergeBatchId": "MERGE-uuid-1234",
        "createTime": "2026-05-01T12:00:00",
        "updateTime": "2026-05-01T12:00:01",
        "refundDetails": []
    }
}
```

---

### 3.11 查询锁库存单据接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | GET /api/v1/inventory/lock-order/{lockOrderId} |
| 接口描述 | 查询指定锁库存单据详情 |
| 调用方 | 运维/管理后台 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lockOrderId | String | 是 | 锁库存单据ID（路径参数） |

#### 响应参数

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "lockOrderId": "1234567890",
        "skuId": 10001,
        "lockQuantity": 10000,
        "bucketInfo": {
            "bucketCount": 16,
            "bucketKeys": ["inventory:lock:1234567890:bucket:0", "..."]
        },
        "expireTime": "2026-05-01T13:00:00",
        "status": "ACTIVE",
        "idempotentKey": "lock-20260501-10001-001",
        "mergeCompleted": false,
        "createTime": "2026-05-01T12:00:00",
        "statistics": {
            "pendingCount": 50,
            "mergedCount": 100,
            "cancelledCount": 5,
            "totalDeducted": 500,
            "totalRemaining": 9500
        }
    }
}
```

## 4 内部服务接口

### 4.1 自动锁库存事件接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口类型 | 异步事件 |
| 事件名 | AutoLockEvent |
| 发布者 | BucketDeductService / AutoLockCheckScheduler |
| 消费者 | AutoLockService |
| 触发条件 | 活跃lockOrder的total_remaining低于阈值 |

#### 事件数据

```json
{
    "skuId": 10001,
    "triggerSource": "DEDUCT_CHECK",
    "currentRemaining": 4500,
    "threshold": 5000,
    "timestamp": 1714543200000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| skuId | Long | 商品ID |
| triggerSource | String | 触发来源：DEDUCT_CHECK(扣减快检)/SCHEDULED_CHECK(定时兜底) |
| currentRemaining | Long | 当前分桶总余量 |
| threshold | Long | 触发阈值 |
| timestamp | Long | 事件时间戳 |

### 4.2 合并提交调度接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口类型 | 定时任务 |
| 调度方式 | Spring @Scheduled |
| 默认间隔 | 1000ms (store.merge.delay-ms) |
| 执行逻辑 | 扫描所有ACTIVE lockOrder，检查合并触发条件 |

#### 调度逻辑

```
1. 查询所有ACTIVE状态的lockOrder
2. 对每个lockOrder检查:
   a. 距创建时间是否超过merge.delay-ms → 延迟触发
   b. total_remaining是否为0 → 分桶耗尽触发
   c. 扣减QPS是否低于idle-qps-threshold → 活跃度衰减触发
3. 满足条件则触发合并提交
```

### 4.3 补偿扫描调度接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口类型 | 定时任务 |
| 调度方式 | Spring @Scheduled |
| 默认间隔 | 5000ms |
| 执行逻辑 | 扫描孤立PENDING明细和崩溃未完成记录 |

#### 调度逻辑

```
1. 扫描孤立PENDING明细:
   SELECT DISTINCT lock_order_id FROM deduction_detail
   WHERE status='PENDING'
   AND lock_order_id IN (SELECT id FROM lock_inventory_order WHERE status='ARCHIVED')

2. 扫描崩溃未完成记录:
   SELECT * FROM lock_inventory_order
   WHERE status='ARCHIVED' AND merge_completed = false
```

### 4.4 锁超时释放调度接口

#### 基本信息

| 项目 | 说明 |
|------|------|
| 接口类型 | 定时任务 |
| 调度方式 | Spring @Scheduled |
| 默认间隔 | 30000ms |
| 执行逻辑 | 扫描过期ACTIVE lockOrder，触发合并提交 |

#### 调度逻辑

```
1. 查询过期ACTIVE lockOrder:
   SELECT * FROM lock_inventory_order
   WHERE status='ACTIVE' AND expire_time < NOW()

2. 对每条记录触发合并提交
3. 发出告警通知
```

## 5 Redis操作接口

### 5.1 Lua脚本接口清单

| 脚本名称 | 功能 | KEYS | ARGV | 返回值 |
|----------|------|------|------|--------|
| deduct.lua | 原子扣减 | bucketKey, totalRemainingKey | quantity | 1=成功, 0=不足 |
| init_buckets.lua | 原子初始化分桶 | bucketKeys[1..N], metaKey, totalRemainingKey | bucketValues[1..N], metaValue, totalRemainingValue | 桶数量 |
| cleanup_buckets.lua | 原子清理分桶 | bucketKeys[1..N], metaKey, totalRemainingKey | 无 | 1 |
| incr_refund.lua | 原子INCR回补 | bucketKey, totalRemainingKey | quantity | 1 |

### 5.2 Redis Key操作接口

| 操作 | Key模式 | 命令 | 使用场景 |
|------|---------|------|----------|
| 读取分桶余量 | inventory:lock:{lockOrderId}:bucket:{n} | GET | 扣减前检查(可选) |
| 读取分桶索引 | inventory:lock:{lockOrderId}:meta | GET | 扣减时获取桶列表 |
| 删除分桶索引 | inventory:lock:{lockOrderId}:meta | DEL | 合并提交扣减屏障 |
| 读取总余量 | inventory:lock:{lockOrderId}:total_remaining | GET | 余量阈值检测 |
| 读取活跃路由 | inventory:active_lock:{skuId} | GET | 扣减时路由解析 |
| 更新活跃路由 | inventory:active_lock:{skuId} | SET | 锁库存完成后 |
| 读取历史路由 | inventory:active_lock_history:{skuId} | LRANGE | 路由兜底 |
| 追加历史路由 | inventory:active_lock_history:{skuId} | LPUSH | 锁库存完成后 |
| 移除历史路由 | inventory:active_lock_history:{skuId} | LREM | 合并提交完成后 |

## 6 接口调用约束

### 6.1 调用顺序约束

| 约束 | 说明 |
|------|------|
| 先锁库存再扣减 | 扣减接口依赖活跃lockOrder，需先完成锁库存操作 |
| 先扣减再付款确认 | 付款确认接口要求明细状态为MERGED |
| 先付款确认再退款 | 退款接口要求明细状态为OCCUPIED |
| 取消仅限付款前 | OCCUPIED状态不可取消，需走退款流程 |

### 6.2 并发调用约束

| 约束 | 说明 |
|------|------|
| 同一订单同一SKU不可并发扣减 | (order_id, sku_id)唯一索引保证 |
| 同一lockOrder不可并发合并提交 | 分布式锁(merge:{lockOrderId})保证 |
| 同一幂等键不可并发锁库存 | uk_idempotent_key唯一索引保证 |

### 6.3 超时与重试

| 接口 | 建议超时 | 重试策略 |
|------|----------|----------|
| 锁库存 | 5s | 幂等键去重，可安全重试 |
| 扣减 | 3s | (order_id, sku_id)去重，可安全重试 |
| 付款确认 | 3s | 状态检查，可安全重试 |
| 取消 | 3s | 状态检查，可安全重试 |
| 退款 | 3s | refundId幂等，可安全重试 |
| 合并触发 | 10s | 分布式锁保护，不建议重试 |
| 紧急解锁 | 10s | 幂等，可安全重试 |
