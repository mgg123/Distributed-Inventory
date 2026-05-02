package com.mgg.exp.store.domain.refund.service;

import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.domain.refund.entity.RefundDetail;

public class RefundDomainService {

    public RefundDetail createRefundDetail(String id, SkuId skuId, Quantity refundQuantity,
                                            DeductPath deductPath, String orderId,
                                            String refDetailId, String refundRequestId) {
        RefundDetail detail = new RefundDetail();
        detail.setId(id);
        detail.setSkuId(skuId);
        detail.setRefundQuantity(refundQuantity);
        detail.setDeductPath(deductPath);
        detail.setOrderId(orderId);
        detail.setRefDetailId(refDetailId);
        detail.setRefundRequestId(refundRequestId);
        return detail;
    }
}
