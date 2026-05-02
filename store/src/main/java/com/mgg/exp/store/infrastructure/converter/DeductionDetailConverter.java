package com.mgg.exp.store.infrastructure.converter;

import com.mgg.exp.store.infrastructure.dataobject.DeductionDetailPO;
import org.springframework.stereotype.Component;

@Component
public class DeductionDetailConverter {

    public DeductionDetailPO toPO(String id, Long skuId, Integer quantity,
                                  String deductPath, Integer bucketIndex,
                                  String status, String orderId,
                                  String lockOrderId) {
        DeductionDetailPO po = new DeductionDetailPO();
        po.setId(id);
        po.setSkuId(skuId);
        po.setQuantity(quantity);
        po.setDeductPath(deductPath);
        po.setBucketIndex(bucketIndex);
        po.setStatus(status);
        po.setOrderId(orderId);
        po.setLockOrderId(lockOrderId);
        return po;
    }
}
