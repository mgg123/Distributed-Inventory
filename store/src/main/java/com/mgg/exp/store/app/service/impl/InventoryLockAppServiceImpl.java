package com.mgg.exp.store.app.service.impl;

import com.mgg.exp.store.app.service.InventoryLockAppService;
import com.mgg.exp.store.common.enums.ErrorCodeEnum;
import com.mgg.exp.store.common.exception.InventoryException;
import com.mgg.exp.store.common.exception.LockOrderAlreadyArchivedException;
import com.mgg.exp.store.common.exception.LockQuantityExceededException;
import com.mgg.exp.store.common.util.IdGenerator;
import com.mgg.exp.store.domain.gateway.ActiveLockRouterGateway;
import com.mgg.exp.store.domain.gateway.DistributedLockGateway;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.InventoryRepository;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderStatus;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryLockAppServiceImpl implements InventoryLockAppService {

    private final InventoryRepository inventoryRepository;
    private final LockOrderRepository lockOrderRepository;
    private final RedisBucketGateway redisBucketGateway;
    private final ActiveLockRouterGateway routerGateway;
    private final DistributedLockGateway distributedLockGateway;
    private final StoreProperties storeProperties;

    @Override
    public LockResult lockInventory(Long skuId, Integer lockQuantity, String idempotentKey,
                                     Double reserveRatio) {
        SkuId skuIdVo = new SkuId(skuId);
        double ratio = reserveRatio != null ? reserveRatio
                : storeProperties.getAutoLock().getReserveRatio();

        Optional<LockInventoryOrder> existing = lockOrderRepository.findByIdempotentKey(idempotentKey);
        if (existing.isPresent()) {
            LockInventoryOrder order = existing.get();
            if (order.getStatus() == LockOrderStatus.ARCHIVED) {
                throw new LockOrderAlreadyArchivedException();
            }
            return LockResult.idempotentHit(order.getId().value());
        }

        var inventoryOpt = inventoryRepository.findBySkuId(skuIdVo);
        if (inventoryOpt.isEmpty()) {
            throw new InventoryException(ErrorCodeEnum.STOCK_NOT_FOUND);
        }
        var inventory = inventoryOpt.get();
        LockResult lockResult = inventory.lock(Quantity.of(lockQuantity), ratio);
        if (!lockResult.isSuccess()) {
            throw new LockQuantityExceededException();
        }

        int actualLockQuantity = lockResult.getActualLockQuantity().getValue();
        int bucketCount = storeProperties.getBucket().getCount();
        int quantityPerBucket = actualLockQuantity / bucketCount;

        String lockOrderId = IdGenerator.nextIdStr();

        boolean redisInit = redisBucketGateway.initBuckets(lockOrderId, skuId,
                bucketCount, quantityPerBucket);
        if (!redisInit) {
            throw new InventoryException(ErrorCodeEnum.INTERNAL_ERROR,
                    "Redis bucket init failed");
        }

        try {
            executeDbTransaction(skuId, actualLockQuantity, lockOrderId,
                    bucketCount, quantityPerBucket, idempotentKey);
        } catch (Exception e) {
            log.error("DB transaction failed, cleaning up Redis buckets for lockOrderId: {}",
                    lockOrderId, e);
            redisBucketGateway.cleanupBuckets(lockOrderId, bucketCount);
            throw e;
        }

        updateRouteWithRetry(skuId, lockOrderId);

        log.info("lockInventory success, skuId: {}, lockOrderId: {}, actualLockQuantity: {}",
                skuId, lockOrderId, actualLockQuantity);
        return LockResult.success(lockOrderId,
                Quantity.of(actualLockQuantity), lockResult.getReservedQuantity());
    }

    @Override
    public void releaseLock(String lockOrderId) {
        LockOrderId id = new LockOrderId(lockOrderId);
        Optional<LockInventoryOrder> orderOpt = lockOrderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            return;
        }
        LockInventoryOrder order = orderOpt.get();
        if (!order.isActive()) {
            return;
        }
        int lockQuantity = order.getLockQuantity().getValue();
        inventoryRepository.emergencyResetLq(order.getSkuId());
        lockOrderRepository.archiveAllBySkuId(order.getSkuId().value());
        redisBucketGateway.cleanupBuckets(lockOrderId,
                storeProperties.getBucket().getCount());
        log.info("releaseLock success, lockOrderId: {}", lockOrderId);
    }

    @Transactional
    protected void executeDbTransaction(Long skuId, int actualLockQuantity,
                                         String lockOrderId, int bucketCount,
                                         int quantityPerBucket, String idempotentKey) {
        int updated = inventoryRepository.lockInventory(new SkuId(skuId), actualLockQuantity);
        if (updated == 0) {
            throw new LockQuantityExceededException();
        }

        String bucketInfo = "{\"bucketCount\":" + bucketCount
                + ",\"quantityPerBucket\":" + quantityPerBucket + "}";
        LocalDateTime expireTime = LocalDateTime.now()
                .plusSeconds(storeProperties.getAutoLock().getExpireSeconds());

        LockInventoryOrder lockOrder = new LockInventoryOrder(
                new LockOrderId(lockOrderId),
                new SkuId(skuId),
                Quantity.of(actualLockQuantity),
                bucketInfo,
                expireTime,
                idempotentKey
        );
        lockOrderRepository.save(lockOrder);
    }

    private void updateRouteWithRetry(Long skuId, String lockOrderId) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                routerGateway.addToHistory(skuId, lockOrderId);
                routerGateway.setActiveLock(skuId, lockOrderId);
                return;
            } catch (Exception e) {
                log.warn("route update retry {}/{}, skuId: {}", i + 1, maxRetries, skuId, e);
                if (i == maxRetries - 1) {
                    log.error("route update failed after {} retries, skuId: {}, lockOrderId: {}",
                            maxRetries, skuId, lockOrderId, e);
                }
            }
        }
    }
}
