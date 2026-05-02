# 基于Redis分布式强一致库存扣减系统 — 数据库设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 系统名称 | 基于Redis分布式强一致库存扣减系统 |
| 文档版本 | V2.0 |
| 编写日期 | 2026-05-02 |
| 文档状态 | 更新（同步spec.md第六轮评审修复） |

## 1 引言

### 1.1 编写目的

本文档为"基于Redis分布式强一致库存扣减系统"的数据库设计文档，详细描述系统所涉及的数据库表结构、字段定义、索引设计、约束条件、Redis数据结构以及数据一致性保障策略，为数据库建表和数据访问层编码提供直接指导。

### 1.2 数据库环境

| 项目 | 说明 |
|------|------|
| 数据库类型 | MySQL 8.x |
| 存储引擎 | InnoDB |
| 字符集 | utf8mb4 |
| 排序规则 | utf8mb4_general_ci |
| 事务隔离级别 | READ_COMMITTED |
| ID生成策略 | 雪花算法 (Snowflake) |

### 1.3 缓存环境

| 项目 | 说明 |
|------|------|
| 缓存类型 | Redis 7.x Cluster |
| 客户端 | Redisson |
| 序列化 | JSON (分桶索引元数据) |
| Key命名规范 | inventory:{功能域}:{标识} |
| Hash Tag规范 | 同一实体的Key使用 `{实体ID}` 确保在同一hash slot，兼容Redis Cluster |

## 2 ER关系图

```
┌────────────────────┐       ┌──────────────────────────┐
│     inventory      │       │   lock_inventory_order   │
│────────────────────│       │──────────────────────────│
│ id (PK)            │◄──┐   │ id (PK)                  │
│ sq                 │   │   │ sku_id (FK → inventory)  │
│ wq                 │   │   │ lock_quantity            │
│ oq                 │   │   │ bucket_info              │
│ lq                 │   │   │ expire_time              │
│                    │   │   │ status                   │
└────────────────────┘   │   │ idempotent_key (UQ)      │
                         │   │ merge_completed          │
                         │   │ create_time              │
                         │   └──────────┬───────────────┘
                         │              │ 1
                         │              │
                         │              │ N
                         │   ┌──────────┴───────────────┐
                         │   │    deduction_detail       │
                         │   │──────────────────────────│
                         │   │ id (PK)                  │
                         │   │ sku_id (FK → inventory)  │
                         │   │ quantity                 │
                         │   │ deduct_path              │
                         │   │ bucket_index             │
                         │   │ status                   │
                         │   │ order_id                 │
                         │   │ lock_order_id (FK)       │──┐
                         │   │ merge_batch_id           │  │
                         │   │ create_time              │  │
                         │   └──────────┬───────────────┘  │
                         │              │ 1                 │
                         │              │                   │
                         │              │ N                 │
                         │   ┌──────────┴───────────────┐  │
                         │   │     refund_detail        │  │
                         │   │──────────────────────────│  │
                         │   │ id (PK)                  │  │
                         │   │ sku_id (FK → inventory)  │  │
                         │   │ refund_quantity          │  │
                         │   │ deduct_path              │  │
                         │   │ status                   │  │
                         │   │ order_id                 │  │
                         │   │ ref_detail_id (FK)       │──┘
                         │   │ create_time              │
                         │   └──────────────────────────┘
                         │
                         └─── lq字段与lock_inventory_order的lock_quantity关联
                              (lq = SUM(ACTIVE lockOrders的lock_quantity))
```

## 3 表结构设计

### 3.1 inventory (库存主表)

#### 用途

存储SKU维度的库存数量信息，包含四种库存字段：可售库存(sq)、预扣库存(wq)、占用库存(oq)、预锁库存(lq)。

#### DDL

```sql
CREATE TABLE `inventory` (
    `id`          BIGINT       NOT NULL COMMENT 'SKU标识，主键',
    `sq`          INT          NOT NULL DEFAULT 0 COMMENT '可售库存(Saleable Quantity)',
    `wq`          INT          NOT NULL DEFAULT 0 COMMENT '预扣库存(Withheld Quantity)',
    `oq`          INT          NOT NULL DEFAULT 0 COMMENT '占用库存(Occupied Quantity)',
    `lq`          INT          NOT NULL DEFAULT 0 COMMENT '预锁库存(Locked Quantity)',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `chk_non_negative` CHECK (`sq` >= 0 AND `wq` >= 0 AND `oq` >= 0 AND `lq` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='库存主表';
```

#### 字段说明

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | BIGINT | 是 | - | SKU标识，主键 |
| sq | INT | 是 | 0 | 可售库存，用户可见的可购买数量 |
| wq | INT | 是 | 0 | 预扣库存，下单后从sq转移到wq |
| oq | INT | 是 | 0 | 占用库存，付款后从wq转移到oq |
| lq | INT | 是 | 0 | 预锁库存，所有ACTIVE lockOrder的lockQuantity之和 |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间，自动维护 |

#### 约束说明

| 约束名 | 类型 | 定义 | 说明 |
|--------|------|------|------|
| chk_non_negative | CHECK | sq >= 0 AND wq >= 0 AND oq >= 0 AND lq >= 0 | 防止库存字段变负 |

#### 核心SQL操作

```sql
-- 锁库存：增加lq
UPDATE inventory SET lq = lq + #{actualLockQuantity}
WHERE id = #{skuId} AND sq - lq >= #{actualLockQuantity};

-- DB降级直接扣减：sq减少，wq增加
UPDATE inventory SET sq = sq - #{quantity}, wq = wq + #{quantity}
WHERE id = #{skuId} AND sq - lq >= #{quantity};

-- 合并提交：sq减少，wq增加，lq减量更新
UPDATE inventory
SET sq = sq - #{netDeduction}, wq = wq + #{netDeduction}, lq = lq - #{currentLockQuantity}
WHERE id = #{skuId} AND sq >= #{netDeduction} AND lq >= #{currentLockQuantity};

-- 补偿合并：sq减少，wq增加
UPDATE inventory SET sq = sq - #{netDeduction}, wq = wq + #{netDeduction}
WHERE id = #{skuId} AND sq >= #{netDeduction};

-- 付款确认：wq减少，oq增加
UPDATE inventory SET wq = wq - #{quantity}, oq = oq + #{quantity}
WHERE id = #{skuId} AND wq >= #{quantity};

-- MERGED取消回补：wq减少，sq增加
UPDATE inventory SET wq = wq - #{quantity}, sq = sq + #{quantity}
WHERE id = #{skuId} AND wq >= #{quantity};

-- OCCUPIED退款回补：oq减少，sq增加
UPDATE inventory SET oq = oq - #{quantity}, sq = sq + #{quantity}
WHERE id = #{skuId} AND oq >= #{quantity};
```

---

### 3.2 lock_inventory_order (锁库存单据表)

#### 用途

记录每次锁库存操作的父单据，包含锁定量、Redis分桶信息、过期时间、状态等。作为扣减明细的父单据，管理锁库存的完整生命周期（ACTIVE → ARCHIVED）。

#### DDL

```sql
CREATE TABLE `lock_inventory_order` (
    `id`               VARCHAR(64)  NOT NULL COMMENT '单据ID/lockOrderId，全局唯一，主键',
    `sku_id`           BIGINT       NOT NULL COMMENT '商品ID/SKU',
    `lock_quantity`    INT          NOT NULL COMMENT '锁定数量(lq变更量)',
    `bucket_info`      JSON         NOT NULL COMMENT 'Redis分桶信息(分桶数量、各桶Key列表)',
    `expire_time`      DATETIME     NOT NULL COMMENT '过期时间',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/ARCHIVED',
    `idempotent_key`   VARCHAR(128) NOT NULL COMMENT '幂等键，唯一索引',
    `merge_completed`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '合并完成标记: 0-未完成, 1-已完成',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotent_key` (`idempotent_key`),
    INDEX `idx_sku_status` (`sku_id`, `status`),
    INDEX `idx_expire_status` (`expire_time`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='锁库存单据表';
```

#### 字段说明

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | VARCHAR(64) | 是 | - | 单据ID/lockOrderId，雪花算法生成 |
| sku_id | BIGINT | 是 | - | 商品ID/SKU |
| lock_quantity | INT | 是 | - | 锁定数量，即lq变更量 |
| bucket_info | JSON | 是 | - | Redis分桶信息，包含分桶数量和各桶Key列表 |
| expire_time | DATETIME | 是 | - | 过期时间，超时后自动触发合并提交释放 |
| status | VARCHAR(16) | 是 | ACTIVE | 状态：ACTIVE(活跃)/ARCHIVED(归档) |
| idempotent_key | VARCHAR(128) | 是 | - | 幂等键，保证同一锁库存请求不重复执行 |
| merge_completed | TINYINT(1) | 是 | 0 | 合并完成标记，0=Redis分桶未清理完成，1=已清理完成 |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |

#### 索引说明

| 索引名 | 类型 | 字段 | 说明 |
|--------|------|------|------|
| PRIMARY | 主键 | id | 主键索引 |
| uk_idempotent_key | 唯一索引 | idempotent_key | 幂等键唯一约束，防止重复锁库存 |
| idx_sku_status | 普通索引 | sku_id, status | 按SKU和状态查询活跃lockOrder |
| idx_expire_status | 普通索引 | expire_time, status | 扫描过期活跃lockOrder |

#### bucket_info JSON结构

```json
{
    "bucketCount": 16,
    "bucketKeys": [
        "inventory:lock:{lockOrderId}:bucket:0",
        "inventory:lock:{lockOrderId}:bucket:1",
        "...",
        "inventory:lock:{lockOrderId}:bucket:15"
    ],
    "metaKey": "inventory:lock:{lockOrderId}:meta",
    "totalRemainingKey": "inventory:lock:{lockOrderId}:total_remaining"
}
```

#### 状态流转

```
ACTIVE (活跃期)
  ├── 创建：锁库存操作时，lq增加，Redis分桶初始化
  ├── 职责：接受新的合并下单明细扣减
  └── 退出条件：合并提交完成
       │
       ▼
ARCHIVED (归档期)
  ├── 进入：合并提交完成后自动进入
  ├── 职责：供子单据关联查询、库存回收、对账审计
  └── 退出条件：关联的所有合并下单明细均到达终态
```

---

### 3.3 deduction_detail (扣减明细表)

#### 用途

记录每次库存扣减的明细信息，包含合并下单明细（Redis预扣减路径）和普通下单明细（DB降级路径）。支持5种状态的状态机流转，是扣减操作的真相源。

#### DDL

```sql
CREATE TABLE `deduction_detail` (
    `id`              VARCHAR(64)  NOT NULL COMMENT '单据ID，全局唯一，主键',
    `sku_id`          BIGINT       NOT NULL COMMENT '商品ID/SKU',
    `quantity`        INT          NOT NULL COMMENT '扣减数量',
    `deduct_path`     VARCHAR(16)  NOT NULL COMMENT '扣减路径: MERGE_BUCKETS/DIRECT_DB',
    `bucket_index`    INT          DEFAULT NULL COMMENT '桶标识，MERGE_BUCKETS路径必填，DIRECT_DB路径为NULL',
    `status`          VARCHAR(16)  NOT NULL COMMENT '状态: PENDING/MERGED/OCCUPIED/CANCELLED/REFUNDED',
    `order_id`        VARCHAR(64)  NOT NULL COMMENT '关联订单ID，必填',
    `lock_order_id`   VARCHAR(64)  DEFAULT NULL COMMENT '关联锁库存单据ID，MERGE_BUCKETS路径必填',
    `merge_batch_id`  VARCHAR(64)  DEFAULT NULL COMMENT '合并批次ID，合并时填充，用于幂等防护',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sku` (`order_id`, `sku_id`),
    INDEX `idx_lock_order_status` (`lock_order_id`, `status`),
    INDEX `idx_merge_batch` (`merge_batch_id`),
    INDEX `idx_sku_status` (`sku_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='扣减明细表';
```

#### 字段说明

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | VARCHAR(64) | 是 | - | 单据ID，雪花算法生成 |
| sku_id | BIGINT | 是 | - | 商品ID/SKU |
| quantity | INT | 是 | - | 扣减数量 |
| deduct_path | VARCHAR(16) | 是 | - | 扣减路径：MERGE_BUCKETS(合并下单明细)/DIRECT_DB(普通下单明细) |
| bucket_index | INT | 否 | NULL | 桶标识，记录扣减发生的Redis桶编号，用于INCR回补时精确恢复 |
| status | VARCHAR(16) | 是 | - | 状态：PENDING/MERGED/OCCUPIED/CANCELLED/REFUNDED |
| order_id | VARCHAR(64) | 是 | - | 关联订单ID，必填，用于幂等和回补关联 |
| lock_order_id | VARCHAR(64) | 否 | NULL | 关联锁库存单据ID，MERGE_BUCKETS路径必填，DIRECT_DB路径为NULL |
| merge_batch_id | VARCHAR(64) | 否 | NULL | 合并批次ID，合并提交时填充，前缀MERGE-{uuid}或COMP-{uuid} |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| update_time | DATETIME | 是 | CURRENT_TIMESTAMP | 更新时间 |

#### 索引说明

| 索引名 | 类型 | 字段 | 说明 |
|--------|------|------|------|
| PRIMARY | 主键 | id | 主键索引 |
| uk_order_sku | 唯一索引 | order_id, sku_id | 扣减幂等硬约束，同一订单同一SKU只能有一条扣减明细 |
| idx_lock_order_status | 普通索引 | lock_order_id, status | 按lockOrder查询待合并明细 |
| idx_merge_batch | 普通索引 | merge_batch_id | 按合并批次查询明细 |
| idx_sku_status | 普通索引 | sku_id, status | 按SKU和状态查询 |

#### 明细分类

| 明细类型 | deduct_path | status起点 | lock_order_id | bucket_index | 说明 |
|----------|-------------|-----------|---------------|-------------|------|
| 合并下单明细 | MERGE_BUCKETS | PENDING | 必填 | 必填 | 走Redis分桶预扣减 |
| 普通下单明细 | DIRECT_DB | MERGED | NULL | NULL | 走DB直接扣减 |

#### 状态机

```
【合并下单明细】(deduct_path=MERGE_BUCKETS)

PENDING ──合并提交──→ MERGED ──付款确认──→ OCCUPIED ──退款──→ REFUNDED
   │                     │
   └──取消(付款前)──→ CANCELLED
                         │
                         └──取消(付款前)──→ CANCELLED

【普通下单明细】(deduct_path=DIRECT_DB)

MERGED ──付款确认──→ OCCUPIED ──退款──→ REFUNDED
   │
   └──取消(付款前)──→ CANCELLED
```

#### 状态转换规则

| 当前状态 | 触发事件 | 适用路径 | 目标状态 | DB库存操作 | Redis操作 |
|----------|----------|----------|----------|-----------|-----------|
| PENDING | 合并提交 | MERGE_BUCKETS | MERGED | sq减少, wq增加, lq减量 | 分桶清除 |
| PENDING | 取消(付款前) | MERGE_BUCKETS | CANCELLED | 无 | 条件INCR回补 |
| MERGED | 付款确认 | 两条路径 | OCCUPIED | wq减少, oq增加 | 无 |
| MERGED | 取消(付款前) | 两条路径 | CANCELLED | wq减少, sq增加 | 无 |
| OCCUPIED | 退款 | 两条路径 | REFUNDED | oq减少, sq增加 | 无 |

---

### 3.4 refund_detail (回补明细表)

#### 用途

记录取消/退款时的回补明细信息，关联原始扣减明细。支持部分退款（买10件退3件），天然支持多次部分退款。主键作为天然幂等键，保证退款操作幂等。

#### DDL

```sql
CREATE TABLE `refund_detail` (
    `id`                  VARCHAR(64)  NOT NULL COMMENT '单据ID，全局唯一，主键',
    `sku_id`              BIGINT       NOT NULL COMMENT '商品ID/SKU',
    `refund_quantity`     INT          NOT NULL COMMENT '回补数量',
    `deduct_path`         VARCHAR(16)  NOT NULL COMMENT '扣减路径: 同原明细 MERGE_BUCKETS/DIRECT_DB',
    `status`              VARCHAR(16)  NOT NULL DEFAULT 'MERGED' COMMENT '状态: MERGED，创建即生效',
    `order_id`            VARCHAR(64)  NOT NULL COMMENT '关联订单ID',
    `ref_detail_id`       VARCHAR(64)  NOT NULL COMMENT '关联原扣减明细ID',
    `refund_request_id`   VARCHAR(128) DEFAULT NULL COMMENT '退款请求标识，业务级幂等键，由调用方传入',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ref_detail_request` (`ref_detail_id`, `refund_request_id`),
    INDEX `idx_ref_detail` (`ref_detail_id`),
    INDEX `idx_order` (`order_id`),
    INDEX `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='回补明细表';
```

#### 字段说明

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | VARCHAR(64) | 是 | - | 单据ID，雪花算法生成 |
| sku_id | BIGINT | 是 | - | 商品ID/SKU |
| refund_quantity | INT | 是 | - | 回补数量，支持部分退款 |
| deduct_path | VARCHAR(16) | 是 | - | 扣减路径，同原明细 |
| status | VARCHAR(16) | 是 | MERGED | 状态，创建即生效 |
| order_id | VARCHAR(64) | 是 | - | 关联订单ID |
| ref_detail_id | VARCHAR(64) | 是 | - | 关联原扣减明细ID，外键关联deduction_detail |
| refund_request_id | VARCHAR(128) | 否 | NULL | 退款请求标识，业务级幂等键。由调用方（如支付系统）传入，同一退款请求的唯一标识。为NULL时退化为仅依赖主键幂等。**注意**：MySQL InnoDB中NULL值不参与唯一约束比较，建议调用方始终传入 |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |

#### 索引说明

| 索引名 | 类型 | 字段 | 说明 |
|--------|------|------|------|
| PRIMARY | 主键 | id | 主键索引 |
| uk_ref_detail_request | 唯一索引 | ref_detail_id, refund_request_id | 业务级退款幂等约束，同一扣减明细的同一退款请求只能有一条回补记录。refund_request_id为NULL时不参与唯一约束比较 |
| idx_ref_detail | 普通索引 | ref_detail_id | 按原扣减明细查询回补记录 |
| idx_order | 普通索引 | order_id | 按订单查询回补记录 |
| idx_sku | 普通索引 | sku_id | 按SKU查询回补记录 |

#### 为什么需要独立的回补明细模型

| 场景 | 无回补明细(在原明细上加字段) | 独立回补明细 |
|------|---------------------------|-------------|
| 全额退款 | 简单，UPDATE原明细即可 | 多一次INSERT |
| 部分退款 | 状态机膨胀+时间线丢失 | 每次退款独立记录，天然支持 |
| 退款幂等 | 靠状态判断，不可靠 | 主键幂等，INSERT冲突即跳过 |
| 合并提交净扣减计算 | CANCELLED记录被遗漏，净扣减值偏大 | 回补明细参与SUM计算，净扣减值准确 |
| 快照原则 | 修改原有快照，违背不可变 | 退款是反向扣减事件，应有独立记录 |

## 4 Redis数据结构设计

### 4.1 数据结构总览

| Key模式 | 类型 | 用途 | TTL |
|---------|------|------|-----|
| inventory:lock:{lockOrderId}:bucket:{n} | STRING(int) | 分桶库存计数器 | 与lockOrder过期时间一致 |
| inventory:lock:{lockOrderId}:meta | STRING(JSON) | 分桶索引元数据 | 与lockOrder过期时间一致 |
| inventory:lock:{lockOrderId}:total_remaining | STRING(int) | 分桶总余量 | 与lockOrder过期时间一致 |
| inventory:active_lock:{skuId} | STRING | 当前活跃lockOrderId | 与lockOrder过期时间一致 |
| inventory:active_lock_history:{skuId} | LIST | 历史活跃lockOrderId列表 | 与lockOrder过期时间一致 |

### 4.2 分桶库存计数器

```
Key:    inventory:lock:{lockOrderId}:bucket:{n}
Value:  整数，表示该桶的当前库存余量
操作:   DECRBY (Lua扣减) / INCRBY (Lua回补) / SET (Lua初始化) / DEL (清理)
示例:   inventory:lock:123456:bucket:0 = 625
        inventory:lock:123456:bucket:1 = 625
        ...
        inventory:lock:123456:bucket:15 = 625
```

### 4.3 分桶索引元数据

```
Key:    inventory:lock:{lockOrderId}:meta
Value:  JSON字符串
操作:   SET (Lua初始化) / GET (扣减时读取) / DEL (合并提交时失效/清理)
示例:
{
    "bucketCount": 16,
    "skuId": 10001,
    "bucketKeyPattern": "inventory:lock:123456:bucket:",
    "bucketKeys": [
        "inventory:lock:123456:bucket:0",
        "inventory:lock:123456:bucket:1",
        "...",
        "inventory:lock:123456:bucket:15"
    ]
}
```

### 4.4 分桶总余量

```
Key:    inventory:lock:{lockOrderId}:total_remaining
Value:  整数，表示该lockOrder所有分桶的库存总余量
操作:   DECRBY (Lua扣减时同步减少) / INCRBY (Lua回补时同步增加)
        SET (Lua初始化) / GET (余量阈值检测) / DEL (清理)
示例:   inventory:lock:123456:total_remaining = 10000
```

### 4.5 活跃lockOrder路由缓存

```
Key:    inventory:active_lock:{skuId}
Value:  lockOrderId，当前活跃的锁库存单据ID
操作:   SET (锁库存创建后更新) / GET (扣减时路由解析) / DEL (无活跃lockOrder时)
示例:   inventory:active_lock:10001 = "123456"
```

### 4.6 历史活跃lockOrder列表

```
Key:    inventory:active_lock_history:{skuId}
Value:  List[lockOrderId]，最近N个活跃的lockOrderId
操作:   LPUSH (新lockOrder创建时追加) / LRANGE (历史路由兜底遍历)
        LREM (合并提交完成后移除ARCHIVED的lockOrderId)
示例:   inventory:active_lock_history:10001 = ["123456", "123455", "123454"]
```

## 5 数据一致性保障

### 5.1 Redis与DB一致性模型

```
┌─────────────────────────────────────────────────────────┐
│                    一致性保障层级                         │
│                                                          │
│  Layer 1: Redis Lua原子操作                              │
│  → 分桶初始化原子性 (全部成功或全部不初始化)                │
│  → 扣减原子性 (检查+扣减+total_remaining同步)             │
│  → 回补原子性 (INCR+total_remaining同步)                  │
│  → 清理原子性 (全部DEL)                                   │
│                                                          │
│  Layer 2: DB事务原子性                                   │
│  → 锁库存: UPDATE inventory + INSERT lock_order 同一事务  │
│  → 合并提交: 先标记后计算 + 库存更新 同一事务              │
│  → DB降级扣减: UPDATE inventory + INSERT deduction 同一事务│
│                                                          │
│  Layer 3: 严格时序控制                                   │
│  → 锁库存: Redis先 → DB事务 → 路由更新最后               │
│  → DB失败时Lua原子回滚Redis                              │
│                                                          │
│  Layer 4: 补偿机制                                       │
│  → 崩溃恢复: 启动时扫描merge_completed=false              │
│  → 孤立明细: 定时扫描PENDING+ARCHIVED                    │
│  → 超时释放: 定时扫描过期lockOrder                       │
└─────────────────────────────────────────────────────────┘
```

### 5.2 关键一致性约束

| 约束 | SQL定义 | 保障场景 |
|------|---------|----------|
| 锁库存不超锁 | WHERE sq - lq >= lockQuantity | 防止lq超过sq |
| DB降级不超卖 | WHERE sq - lq >= quantity | 防止DB降级侵占lq锁定库存 |
| 合并提交不超卖 | WHERE sq >= #{net_deduction} AND lq >= #{currentLockQuantity} | 最终防线，防止sq/lq变负 |
| 库存字段非负 | CHECK (sq>=0 AND wq>=0 AND oq>=0 AND lq>=0) | 防止任何库存字段变负 |
| 扣减幂等 | UNIQUE (order_id, sku_id) | 防止同一订单重复扣减 |
| 锁库存幂等 | UNIQUE (idempotent_key) | 防止重复锁库存 |
| 退款业务幂等 | UNIQUE (ref_detail_id, refund_request_id) | 防止同一扣减明细同一退款请求重复退款 |

### 5.3 lq字段与lock_inventory_order的一致性

inventory表的lq字段是所有ACTIVE状态lock_inventory_order的lockQuantity之和：

```
inventory.lq = SUM(lock_inventory_order.lock_quantity WHERE status='ACTIVE' AND sku_id=...)
```

合并提交时lq减量更新（`lq = lq - #{currentLockQuantity}`）确保每个lockOrder只清除自己的份额，不会错误清除其他仍ACTIVE的lockOrder的lq。

### 5.4 净扣减值计算

合并提交的净扣减值通过以下公式计算：

```
net_deduction = SUM(deduction_detail.quantity WHERE merge_batch_id = #{batchId})
             - SUM(refund_detail.refund_quantity WHERE ref_detail_id IN (已MERGED的明细IDs))
```

其中：
- PENDING状态取消通过Redis INCR回补保障，不创建refund_detail
- MERGED/OCCUPIED状态取消/退款时创建refund_detail参与计算

## 6 数据生命周期

### 6.1 数据保留策略

| 表 | 保留策略 | 归档条件 |
|------|----------|----------|
| inventory | 永久保留 | 随SKU生命周期 |
| lock_inventory_order | 永久保留 | 合并提交后进入ARCHIVED，供对账审计 |
| deduction_detail | 永久保留 | 到达终态(CANCELLED/REFUNDED)后供对账审计 |
| refund_detail | 永久保留 | 供对账审计 |

### 6.2 Redis Key生命周期

| Key | 生命周期 | 清理时机 |
|-----|----------|----------|
| bucket:{n} | 与lockOrder过期时间一致 | 合并提交时DEL / TTL到期自动清理 |
| meta | 与lockOrder过期时间一致 | 合并提交时DEL(扣减屏障) / TTL到期自动清理 |
| total_remaining | 与lockOrder过期时间一致 | 合并提交时DEL / TTL到期自动清理 |
| active_lock:{skuId} | 与lockOrder过期时间一致 | 新lockOrder创建时覆盖 / TTL到期自动清理 |
| active_lock_history:{skuId} | 与lockOrder过期时间一致 | 合并提交后LREM / TTL到期自动清理 |

## 7 容量估算

### 7.1 单SKU数据量估算

以单SKU日销10万单为例：

| 表 | 日增量 | 月增量 | 年增量 |
|------|--------|--------|--------|
| lock_inventory_order | ~10条 | ~300条 | ~3650条 |
| deduction_detail | ~10万条 | ~300万条 | ~3650万条 |
| refund_detail | ~1万条(10%退款率) | ~30万条 | ~365万条 |

### 7.2 存储空间估算

| 表 | 单行大小 | 月增量(单SKU) | 年增量(单SKU) |
|------|----------|---------------|---------------|
| lock_inventory_order | ~1KB | ~300KB | ~3.6MB |
| deduction_detail | ~0.5KB | ~1.5GB | ~18GB |
| refund_detail | ~0.3KB | ~90MB | ~1.1GB |

### 7.3 Redis内存估算

单SKU单lockOrder（16桶）：

| Key类型 | 数量 | 单Key大小 | 总大小 |
|---------|------|-----------|--------|
| bucket:{n} | 16 | ~50B | ~800B |
| meta | 1 | ~1KB | ~1KB |
| total_remaining | 1 | ~50B | ~50B |
| active_lock | 1 | ~50B | ~50B |
| active_lock_history | 1 | ~200B | ~200B |
| **合计** | 20 | - | **~2.1KB** |

1000个热点SKU同时活跃：~2.1MB
