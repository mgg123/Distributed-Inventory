CREATE TABLE IF NOT EXISTS inventory (
    id          BIGINT       NOT NULL,
    sq          INT          NOT NULL DEFAULT 0,
    wq          INT          NOT NULL DEFAULT 0,
    oq          INT          NOT NULL DEFAULT 0,
    lq          INT          NOT NULL DEFAULT 0,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS lock_inventory_order (
    id               VARCHAR(64)  NOT NULL,
    sku_id           BIGINT       NOT NULL,
    lock_quantity    INT          NOT NULL,
    bucket_info      TEXT         NOT NULL,
    expire_time      TIMESTAMP    NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    idempotent_key   VARCHAR(128) NOT NULL,
    merge_completed  SMALLINT     NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (idempotent_key)
);

CREATE INDEX IF NOT EXISTS idx_lo_sku_status ON lock_inventory_order (sku_id, status);
CREATE INDEX IF NOT EXISTS idx_lo_expire_status ON lock_inventory_order (expire_time, status);

CREATE TABLE IF NOT EXISTS deduction_detail (
    id              VARCHAR(64)  NOT NULL,
    sku_id          BIGINT       NOT NULL,
    quantity        INT          NOT NULL,
    deduct_path     VARCHAR(16)  NOT NULL,
    bucket_index    INT          DEFAULT NULL,
    status          VARCHAR(16)  NOT NULL,
    order_id        VARCHAR(64)  NOT NULL,
    lock_order_id   VARCHAR(64)  DEFAULT NULL,
    merge_batch_id  VARCHAR(64)  DEFAULT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (order_id, sku_id)
);

CREATE INDEX IF NOT EXISTS idx_dd_lock_order_status ON deduction_detail (lock_order_id, status);
CREATE INDEX IF NOT EXISTS idx_dd_merge_batch ON deduction_detail (merge_batch_id);
CREATE INDEX IF NOT EXISTS idx_dd_sku_status ON deduction_detail (sku_id, status);

CREATE TABLE IF NOT EXISTS refund_detail (
    id                  VARCHAR(64)  NOT NULL,
    sku_id              BIGINT       NOT NULL,
    refund_quantity     INT          NOT NULL,
    deduct_path         VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'MERGED',
    order_id            VARCHAR(64)  NOT NULL,
    ref_detail_id       VARCHAR(64)  NOT NULL,
    refund_request_id   VARCHAR(128) DEFAULT NULL,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (ref_detail_id, refund_request_id)
);

CREATE INDEX IF NOT EXISTS idx_rd_ref_detail ON refund_detail (ref_detail_id);
CREATE INDEX IF NOT EXISTS idx_rd_order ON refund_detail (order_id);
CREATE INDEX IF NOT EXISTS idx_rd_sku ON refund_detail (sku_id);
