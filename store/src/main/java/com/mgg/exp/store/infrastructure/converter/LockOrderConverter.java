package com.mgg.exp.store.infrastructure.converter;

import com.mgg.exp.store.infrastructure.dataobject.LockInventoryOrderPO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class LockOrderConverter {

    public LockInventoryOrderPO toPO(String id, Long skuId, Integer lockQuantity,
                                     String bucketInfo, LocalDateTime expireTime,
                                     String idempotentKey) {
        LockInventoryOrderPO po = new LockInventoryOrderPO();
        po.setId(id);
        po.setSkuId(skuId);
        po.setLockQuantity(lockQuantity);
        po.setBucketInfo(bucketInfo);
        po.setExpireTime(expireTime);
        po.setStatus("ACTIVE");
        po.setIdempotentKey(idempotentKey);
        po.setMergeCompleted(false);
        return po;
    }
}
