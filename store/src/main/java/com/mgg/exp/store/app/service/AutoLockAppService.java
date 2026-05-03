package com.mgg.exp.store.app.service;

import com.mgg.exp.store.domain.inventory.valueobject.LockResult;

public interface AutoLockAppService {

    LockResult triggerAutoLock(Long skuId, String triggerReason);

    void checkAndAutoLock(Long skuId);
}
