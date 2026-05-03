package com.mgg.exp.store.app.scheduler;

import com.mgg.exp.store.app.service.AutoLockAppService;
import com.mgg.exp.store.domain.gateway.ActiveLockRouterGateway;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AutoLockCheckTask {

    private final AutoLockAppService autoLockAppService;
    private final LockOrderRepository lockOrderRepository;
    private final ActiveLockRouterGateway routerGateway;
    private final RedisBucketGateway redisBucketGateway;
    private final StringRedisTemplate redisTemplate;
    private final StoreProperties storeProperties;

    private static final String AUTO_LOCK_PENDING_KEY = "inventory:{%d}:auto_lock_pending";
    private static final long AUTO_LOCK_PENDING_TTL_SECONDS = 5;

    @Scheduled(fixedDelayString = "${store.auto-lock.check-interval-ms:500}")
    public void checkAutoLock() {
        if (!storeProperties.getAutoLock().isEnabled()) {
            return;
        }

        Set<String> activeSkuIds = findActiveSkuIds();
        for (String skuIdStr : activeSkuIds) {
            try {
                Long skuId = Long.parseLong(skuIdStr);
                String activeLockOrderId = routerGateway.getActiveLock(skuId);
                if (activeLockOrderId == null || activeLockOrderId.isBlank()) {
                    continue;
                }

                int totalRemaining = redisBucketGateway.getTotalRemaining(activeLockOrderId);
                if (totalRemaining < 0) {
                    continue;
                }

                double triggerRatio = storeProperties.getAutoLock().getTriggerRatio();
                int minLockQuantity = storeProperties.getAutoLock().getMinLockQuantity();
                if (totalRemaining <= triggerRatio * minLockQuantity) {
                    String pendingKey = String.format(AUTO_LOCK_PENDING_KEY, skuId);
                    Boolean setSuccess = redisTemplate.opsForValue()
                            .setIfAbsent(pendingKey, "1",
                                    AUTO_LOCK_PENDING_TTL_SECONDS, TimeUnit.SECONDS);
                    if (Boolean.TRUE.equals(setSuccess)) {
                        autoLockAppService.triggerAutoLock(skuId, "SCHEDULED_LOW_REMAINING");
                    }
                }
            } catch (Exception e) {
                log.error("auto lock check failed for skuId: {}", skuIdStr, e);
            }
        }
    }

    private Set<String> findActiveSkuIds() {
        return Set.of();
    }
}
