package com.mgg.exp.store.domain.deduction.entity;

import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.deduction.valueobject.DeductionStatus;
import com.mgg.exp.store.domain.inventory.valueobject.OrderId;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;

public class DeductionDetail {

    private String id;
    private SkuId skuId;
    private Quantity quantity;
    private DeductPath deductPath;
    private Integer bucketIndex;
    private DeductionStatus status;
    private OrderId orderId;
    private String lockOrderId;
    private String mergeBatchId;

    public boolean isPending() {
        return status == DeductionStatus.PENDING;
    }

    public boolean isMerged() {
        return status == DeductionStatus.MERGED;
    }

    public boolean isOccupied() {
        return status == DeductionStatus.OCCUPIED;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public SkuId getSkuId() {
        return skuId;
    }

    public void setSkuId(SkuId skuId) {
        this.skuId = skuId;
    }

    public Quantity getQuantity() {
        return quantity;
    }

    public void setQuantity(Quantity quantity) {
        this.quantity = quantity;
    }

    public DeductPath getDeductPath() {
        return deductPath;
    }

    public void setDeductPath(DeductPath deductPath) {
        this.deductPath = deductPath;
    }

    public Integer getBucketIndex() {
        return bucketIndex;
    }

    public void setBucketIndex(Integer bucketIndex) {
        this.bucketIndex = bucketIndex;
    }

    public DeductionStatus getStatus() {
        return status;
    }

    public void setStatus(DeductionStatus status) {
        this.status = status;
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public void setOrderId(OrderId orderId) {
        this.orderId = orderId;
    }

    public String getLockOrderId() {
        return lockOrderId;
    }

    public void setLockOrderId(String lockOrderId) {
        this.lockOrderId = lockOrderId;
    }

    public String getMergeBatchId() {
        return mergeBatchId;
    }

    public void setMergeBatchId(String mergeBatchId) {
        this.mergeBatchId = mergeBatchId;
    }
}
