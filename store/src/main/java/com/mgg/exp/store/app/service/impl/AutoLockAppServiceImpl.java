package com.mgg.exp.store.app.service.impl;

import com.mgg.exp.store.app.service.AutoLockAppService;
import com.mgg.exp.store.app.service.InventoryLockAppService;
import com.mgg.exp.store.domain.gateway.ActiveLockRouterGateway;
import com.mgg.exp.store.domain.gateway.DistributedLockGateway;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.routing.service.RoutingDomainService;
import com.mgg.exp.store.domain.routing.valueobject.RouteResolveResult;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoLockAppServiceImpl implements AutoLockAppService {

    private final InventoryLockAppService lockAppService;
    private final LockOrderRepository lockOrderRepository;
    private final ActiveLockRouterGateway routerGateway;
    private final RedisBucketGateway redisBucketGateway;
    private final RoutingDomainService routingDomainService;
    private final DistributedLockGateway distributedLockGateway;
    private final StringRedisTemplate redisTemplate;
    private final StoreProperties storeProperties;

    private static final String AUTO_LOCK_PENDING_KEY = "inventory:{%d}:auto_lock_pending";
    private static final long AUTO_LOCK_PENDING_TTL_SECONDS = 5;

    @Override
    public LockResult triggerAutoLock(Long skuId, String triggerReason) {
        if (!storeProperties.getAutoLock().isEnabled()) {
            log.debug("auto lock disabled, skip, skuId: {}", skuId);
            return LockResult.insufficient();
        }

        String pendingKey = String.format(AUTO_LOCK_PENDING_KEY, skuId);
        Boolean setSuccess = redisTemplate.opsForValue()
                .setIfAbsent(pendingKey, "1", AUTO_LOCK_PENDING_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(setSuccess)) {
            log.debug("auto lock already pending, skip, skuId: {}", skuId);
            return LockResult.insufficient();
        }

        try {
            return checkAndAutoLockInternal(skuId, triggerReason);
        } catch (Exception e) {
            log.error("auto lock failed, skuId: {}, reason: {}", skuId, triggerReason, e);
            redisTemplate.delete(pendingKey);
            return LockResult.insufficient();
        }
    }

    @Override
    public void checkAndAutoLock(Long skuId) {
        if (!storeProperties.getAutoLock().isEnabled()) {
            return;
        }

        try {
            checkAndAutoLockInternal(skuId, "SCHEDULED_CHECK");
        } catch (Exception e) {
            log.error("scheduled auto lock check failed, skuId: {}", skuId, e);
        }
    }

    private LockResult checkAndAutoLockInternal(Long skuId, String triggerReason) {
        long activeCount = lockOrderRepository.countActiveBySkuId(skuId);
        int maxActive = storeProperties.getAutoLock().getMaxActive();
        if (activeCount >= maxActive) {
            log.debug("active lock orders reach max: {}, skuId: {}", activeCount, skuId);
            return LockResult.insufficient();
        }

        RouteResolveResult route = routingDomainService.resolveActiveLock(skuId);
        if (route.isFound()) {
            String activeLockOrderId = route.getLockOrderId();
            int totalRemaining = redisBucketGateway.getTotalRemaining(activeLockOrderId);
            if (totalRemaining > 0) {
                int minLockQuantity = storeProperties.getAutoLock().getMinLockQuantity();
                double triggerRatio = storeProperties.getAutoLock().getTriggerRatio();
                if (totalRemaining > triggerRatio * minLockQuantity) {
                    log.debug("active lock has sufficient remaining: {}, skuId: {}",
                            totalRemaining, skuId);
                    return LockResult.insufficient();
                }
            }
        }

        String idempotentKey = "AUTO-LOCK-" + skuId + "-" + System.currentTimeMillis();
        int lockQuantity = storeProperties.getAutoLock().getMinLockQuantity();

        LockResult result = lockAppService.lockInventory(
                skuId, lockQuantity, idempotentKey,
                storeProperties.getAutoLock().getReserveRatio());

        if (result.isSuccess() && result.getActualLockQuantity() != null) {
            log.info("auto lock success, skuId: {}, lockOrderId: {}, actualLockQuantity: {}, " +
                    "triggerReason: {}", skuId, result.getLockOrderId(),
                    result.getActualLockQuantity().getValue(), triggerReason);
        } else {
            log.info("auto lock not triggered, skuId: {}, reason: {}, triggerReason: {}",
                    skuId, result.getErrorCode(), triggerReason);
        }

        return result;
    }
}
