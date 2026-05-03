package com.mgg.exp.store.domain.inventory.entity;

import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderStatus;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;

import java.time.LocalDateTime;

public class LockInventoryOrder {

    private LockOrderId id;
    private SkuId skuId;
    private Quantity lockQuantity;
    private String bucketInfo;
    private LocalDateTime expireTime;
    private LockOrderStatus status;
    private String idempotentKey;
    private boolean mergeCompleted;

    public LockInventoryOrder(LockOrderId id, SkuId skuId, Quantity lockQuantity,
                              String bucketInfo, LocalDateTime expireTime,
                              String idempotentKey) {
        this.id = id;
        this.skuId = skuId;
        this.lockQuantity = lockQuantity;
        this.bucketInfo = bucketInfo;
        this.expireTime = expireTime;
        this.status = LockOrderStatus.ACTIVE;
        this.idempotentKey = idempotentKey;
        this.mergeCompleted = false;
    }

    public LockInventoryOrder(LockOrderId id, SkuId skuId, Quantity lockQuantity,
                              String bucketInfo, LocalDateTime expireTime,
                              LockOrderStatus status, String idempotentKey,
                              boolean mergeCompleted) {
        this.id = id;
        this.skuId = skuId;
        this.lockQuantity = lockQuantity;
        this.bucketInfo = bucketInfo;
        this.expireTime = expireTime;
        this.status = status;
        this.idempotentKey = idempotentKey;
        this.mergeCompleted = mergeCompleted;
    }

    public void archive() {
        this.status = LockOrderStatus.ARCHIVED;
    }

    public void markMergeCompleted() {
        this.mergeCompleted = true;
    }

    public boolean isActive() {
        return status == LockOrderStatus.ACTIVE;
    }

    public boolean isExpired() {
        return expireTime != null && LocalDateTime.now().isAfter(expireTime);
    }

    public LockOrderId getId() {
        return id;
    }

    public SkuId getSkuId() {
        return skuId;
    }

    public Quantity getLockQuantity() {
        return lockQuantity;
    }

    public String getBucketInfo() {
        return bucketInfo;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public LockOrderStatus getStatus() {
        return status;
    }

    public String getIdempotentKey() {
        return idempotentKey;
    }

    public boolean isMergeCompleted() {
        return mergeCompleted;
    }
}
