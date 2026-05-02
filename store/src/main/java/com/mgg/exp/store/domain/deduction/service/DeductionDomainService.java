package com.mgg.exp.store.domain.deduction.service;

import com.mgg.exp.store.domain.deduction.aggregate.DeductionDetailAggregate;
import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import com.mgg.exp.store.domain.deduction.valueobject.DetailId;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.domain.inventory.valueobject.OrderId;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;

public class DeductionDomainService {

    public DeductionDetailAggregate createDeductionDetail(DetailId id, SkuId skuId,
                                                           Quantity quantity, DeductPath deductPath,
                                                           Integer bucketIndex, OrderId orderId,
                                                           LockOrderId lockOrderId) {
        return new DeductionDetailAggregate(id, skuId, quantity, deductPath,
                bucketIndex, orderId, lockOrderId);
    }
}
