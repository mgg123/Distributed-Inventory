package com.mgg.exp.store.infrastructure.gateway;

import com.mgg.exp.store.domain.gateway.ActiveLockRouterGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveLockRouterGatewayImpl implements ActiveLockRouterGateway {

    private final StringRedisTemplate redisTemplate;

    private static final String ACTIVE_LOCK_KEY_PATTERN = "inventory:{%d}:active_lock";
    private static final String HISTORY_KEY_PATTERN = "inventory:{%d}:active_lock_history";
    private static final long ROUTE_TTL_SECONDS = 3600;

    private String activeLockKey(Long skuId) {
        return String.format(ACTIVE_LOCK_KEY_PATTERN, skuId);
    }

    private String historyKey(Long skuId) {
        return String.format(HISTORY_KEY_PATTERN, skuId);
    }

    @Override
    public void setActiveLock(Long skuId, String lockOrderId) {
        String key = activeLockKey(skuId);
        redisTemplate.opsForValue().set(key, lockOrderId, ROUTE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public String getActiveLock(Long skuId) {
        return redisTemplate.opsForValue().get(activeLockKey(skuId));
    }

    @Override
    public void addToHistory(Long skuId, String lockOrderId) {
        redisTemplate.opsForList().rightPush(historyKey(skuId), lockOrderId);
    }

    @Override
    public List<String> getHistory(Long skuId) {
        return redisTemplate.opsForList().range(historyKey(skuId), 0, -1);
    }

    @Override
    public void removeFromHistory(Long skuId, String lockOrderId) {
        redisTemplate.opsForList().remove(historyKey(skuId), 1, lockOrderId);
    }

    @Override
    public void removeActiveLock(Long skuId) {
        redisTemplate.delete(activeLockKey(skuId));
    }
}
