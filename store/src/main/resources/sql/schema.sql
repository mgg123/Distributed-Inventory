CREATE DATABASE IF NOT EXISTS `store` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `store`;

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
