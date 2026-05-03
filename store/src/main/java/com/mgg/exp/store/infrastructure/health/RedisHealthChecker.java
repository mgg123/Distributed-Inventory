package com.mgg.exp.store.infrastructure.health;

import com.mgg.exp.store.app.service.EmergencyAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthChecker {

    private final RedisConnectionFactory redisConnectionFactory;
    private final EmergencyAppService emergencyAppService;

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void checkRedisHealth() {
        try {
            redisConnectionFactory.getConnection().ping();
            emergencyAppService.recordRedisSuccess();
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            emergencyAppService.recordRedisFailure();
        }
    }
}
