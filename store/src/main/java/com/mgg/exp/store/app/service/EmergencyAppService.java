package com.mgg.exp.store.app.service;

public interface EmergencyAppService {

    void emergencyUnlock(Long skuId, boolean force);

    void triggerEmergencyMergeForAll(Long skuId);

    void recordRedisFailure();

    void recordRedisSuccess();
}
