package com.mgg.exp.store.app.service;

import com.mgg.exp.store.domain.inventory.valueobject.LockResult;

public interface InventoryLockAppService {

    LockResult lockInventory(Long skuId, Integer lockQuantity, String idempotentKey,
                             Double reserveRatio);

    void releaseLock(String lockOrderId);
}
