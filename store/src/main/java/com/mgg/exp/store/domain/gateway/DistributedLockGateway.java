package com.mgg.exp.store.domain.gateway;

public interface DistributedLockGateway {

    boolean tryLock(String lockKey, long waitTimeSeconds, long leaseTimeSeconds);

    void unlock(String lockKey);

    boolean isHeldByCurrentThread(String lockKey);
}
