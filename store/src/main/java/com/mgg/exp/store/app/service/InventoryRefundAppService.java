package com.mgg.exp.store.app.service;

import com.mgg.exp.store.domain.refund.valueobject.RefundResult;

public interface InventoryRefundAppService {

    RefundResult cancel(String detailId);

    RefundResult refund(String detailId, Integer quantity, String refundRequestId);

    RefundResult confirmPayment(String detailId);
}
