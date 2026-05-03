package com.mgg.exp.store.app.service;

import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;

public interface InventoryDeductAppService {

    DeductResult deduct(Long skuId, Integer quantity, String orderId);
}
