package com.mgg.exp.store.infrastructure.gateway;

import com.mgg.exp.store.domain.gateway.DistributedLockGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockGatewayImpl implements DistributedLockGateway {

    private final RedissonClient redissonClient;
    private final ConcurrentHashMap<String, RLock> lockHolder = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String lockKey, long waitTimeSeconds, long leaseTimeSeconds) {
        try {
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = lock.tryLock(waitTimeSeconds, leaseTimeSeconds, TimeUnit.SECONDS);
            if (acquired) {
                lockHolder.put(lockKey, lock);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("tryLock interrupted, lockKey: {}", lockKey, e);
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        RLock lock = lockHolder.remove(lockKey);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public boolean isHeldByCurrentThread(String lockKey) {
        RLock lock = lockHolder.get(lockKey);
        return lock != null && lock.isHeldByCurrentThread();
    }
}
