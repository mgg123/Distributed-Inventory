package com.mgg.exp.store.domain.deduction.aggregate;

import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.deduction.valueobject.DeductionStatus;
import com.mgg.exp.store.domain.deduction.valueobject.DetailId;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.domain.inventory.valueobject.OrderId;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;

public class DeductionDetailAggregate {

    private DetailId id;
    private SkuId skuId;
    private Quantity quantity;
    private DeductPath deductPath;
    private Integer bucketIndex;
    private DeductionStatus status;
    private OrderId orderId;
    private LockOrderId lockOrderId;
    private String mergeBatchId;

    public DeductionDetailAggregate(DetailId id, SkuId skuId, Quantity quantity,
                                     DeductPath deductPath, Integer bucketIndex,
                                     OrderId orderId, LockOrderId lockOrderId) {
        this.id = id;
        this.skuId = skuId;
        this.quantity = quantity;
        this.deductPath = deductPath;
        this.bucketIndex = bucketIndex;
        this.status = DeductionStatus.PENDING;
        this.orderId = orderId;
        this.lockOrderId = lockOrderId;
    }

    public void cancel() {
        status.checkTransition(DeductionStatus.CANCELLED);
        this.status = DeductionStatus.CANCELLED;
    }

    public void markMerged(String batchId) {
        status.checkTransition(DeductionStatus.MERGED);
        this.status = DeductionStatus.MERGED;
        this.mergeBatchId = batchId;
    }

    public void confirmOccupied() {
        status.checkTransition(DeductionStatus.OCCUPIED);
        this.status = DeductionStatus.OCCUPIED;
    }

    public void refund() {
        status.checkTransition(DeductionStatus.REFUNDED);
        this.status = DeductionStatus.REFUNDED;
    }

    public boolean isPending() {
        return status == DeductionStatus.PENDING;
    }

    public boolean isMerged() {
        return status == DeductionStatus.MERGED;
    }

    public boolean isOccupied() {
        return status == DeductionStatus.OCCUPIED;
    }

    public DetailId getId() {
        return id;
    }

    public SkuId getSkuId() {
        return skuId;
    }

    public Quantity getQuantity() {
        return quantity;
    }

    public DeductPath getDeductPath() {
        return deductPath;
    }

    public Integer getBucketIndex() {
        return bucketIndex;
    }

    public DeductionStatus getStatus() {
        return status;
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public LockOrderId getLockOrderId() {
        return lockOrderId;
    }

    public String getMergeBatchId() {
        return mergeBatchId;
    }
}
