package com.mgg.exp.store.infrastructure.converter;

import com.mgg.exp.store.infrastructure.dataobject.RefundDetailPO;
import org.springframework.stereotype.Component;

@Component
public class RefundDetailConverter {

    public RefundDetailPO toPO(String id, Long skuId, Integer refundQuantity,
                               String deductPath, String orderId,
                               String refDetailId, String refundRequestId) {
        RefundDetailPO po = new RefundDetailPO();
        po.setId(id);
        po.setSkuId(skuId);
        po.setRefundQuantity(refundQuantity);
        po.setDeductPath(deductPath);
        po.setStatus("MERGED");
        po.setOrderId(orderId);
        po.setRefDetailId(refDetailId);
        po.setRefundRequestId(refundRequestId);
        return po;
    }
}
