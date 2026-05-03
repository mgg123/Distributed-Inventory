package com.mgg.exp.store.app.service.impl;

import com.mgg.exp.store.app.service.EmergencyAppService;
import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.domain.gateway.EmergencyDegradeGateway;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.InventoryRepository;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyAppServiceImpl implements EmergencyAppService {

    private final InventoryMergeAppService mergeAppService;
    private final InventoryRepository inventoryRepository;
    private final LockOrderRepository lockOrderRepository;
    private final EmergencyDegradeGateway emergencyDegradeGateway;
    private final RedisBucketGateway redisBucketGateway;
    private final StoreProperties storeProperties;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    @Override
    public void emergencyUnlock(Long skuId, boolean force) {
        log.warn("emergencyUnlock triggered, skuId: {}, force: {}", skuId, force);

        if (force) {
            List<LockInventoryOrder> activeOrders = lockOrderRepository.findActiveBySkuId(skuId);
            cleanupAllBuckets(activeOrders);
            executeForceUnlock(skuId);
        } else {
            triggerEmergencyMergeForAll(skuId);
        }

        emergencyDegradeGateway.setDegradeFlag(skuId);
        log.warn("emergency degrade flag set, skuId: {}", skuId);
    }

    @Override
    public void triggerEmergencyMergeForAll(Long skuId) {
        List<LockInventoryOrder> activeOrders = lockOrderRepository.findActiveBySkuId(skuId);
        if (activeOrders.isEmpty()) {
            log.info("no active lock orders to merge, skuId: {}", skuId);
            return;
        }

        for (LockInventoryOrder order : activeOrders) {
            try {
                mergeAppService.mergeCommit(order.getId().value());
                log.info("emergency merge commit success, lockOrderId: {}",
                        order.getId().value());
            } catch (Exception e) {
                log.error("emergency merge commit failed, lockOrderId: {}",
                        order.getId().value(), e);
            }
        }
    }

    @Override
    public void recordRedisFailure() {
        int count = consecutiveFailures.incrementAndGet();
        int threshold = storeProperties.getRedis().getFailThreshold();
        if (count >= threshold) {
            log.warn("consecutive Redis failures reached threshold: {}, " +
                    "emergency merge should be triggered", count);
        }
    }

    @Override
    public void recordRedisSuccess() {
        consecutiveFailures.set(0);
    }

    public boolean shouldTriggerEmergency() {
        return consecutiveFailures.get() >= storeProperties.getRedis().getFailThreshold();
    }

    @Transactional
    protected void executeForceUnlock(Long skuId) {
        SkuId skuIdVo = new SkuId(skuId);

        inventoryRepository.emergencyResetLq(skuIdVo);
        log.warn("force reset lq=0, skuId: {}", skuId);

        lockOrderRepository.archiveAllBySkuId(skuId);
        log.warn("archived all lock orders, skuId: {}", skuId);
    }

    private void cleanupAllBuckets(List<LockInventoryOrder> orders) {
        int bucketCount = storeProperties.getBucket().getCount();
        for (LockInventoryOrder order : orders) {
            try {
                redisBucketGateway.cleanupBuckets(order.getId().value(), bucketCount);
            } catch (Exception e) {
                log.error("cleanup buckets failed during emergency, lockOrderId: {}",
                        order.getId().value(), e);
            }
        }
    }
}
