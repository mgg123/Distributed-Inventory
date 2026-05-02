package com.mgg.exp.store.infrastructure.gateway;

import com.mgg.exp.store.domain.gateway.EmergencyDegradeGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyDegradeGatewayImpl implements EmergencyDegradeGateway {

    private final StringRedisTemplate redisTemplate;

    private static final String DEGRADE_KEY_PATTERN = "inventory:{%d}:emergency_degrade";
    private static final long DEGRADE_TTL_SECONDS = 30;

    private String degradeKey(Long skuId) {
        return String.format(DEGRADE_KEY_PATTERN, skuId);
    }

    @Override
    public boolean setDegradeFlag(Long skuId) {
        String key = degradeKey(skuId);
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(key, "true", DEGRADE_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean isDegradeFlagSet(Long skuId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(degradeKey(skuId)));
    }

    @Override
    public void removeDegradeFlag(Long skuId) {
        redisTemplate.delete(degradeKey(skuId));
    }
}
