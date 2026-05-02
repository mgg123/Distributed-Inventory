package com.mgg.exp.store.domain.refund.entity;

import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;

public class RefundDetail {

    private String id;
    private SkuId skuId;
    private Quantity refundQuantity;
    private DeductPath deductPath;
    private String orderId;
    private String refDetailId;
    private String refundRequestId;

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

    public Quantity getRefundQuantity() {
        return refundQuantity;
    }

    public void setRefundQuantity(Quantity refundQuantity) {
        this.refundQuantity = refundQuantity;
    }

    public DeductPath getDeductPath() {
        return deductPath;
    }

    public void setDeductPath(DeductPath deductPath) {
        this.deductPath = deductPath;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getRefDetailId() {
        return refDetailId;
    }

    public void setRefDetailId(String refDetailId) {
        this.refDetailId = refDetailId;
    }

    public String getRefundRequestId() {
        return refundRequestId;
    }

    public void setRefundRequestId(String refundRequestId) {
        this.refundRequestId = refundRequestId;
    }
}
