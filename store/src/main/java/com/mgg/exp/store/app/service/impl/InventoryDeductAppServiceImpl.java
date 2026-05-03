package com.mgg.exp.store.app.service.impl;

import com.mgg.exp.store.app.service.InventoryDeductAppService;
import com.mgg.exp.store.common.enums.ErrorCodeEnum;
import com.mgg.exp.store.common.exception.InsufficientStockException;
import com.mgg.exp.store.common.exception.InventoryException;
import com.mgg.exp.store.common.util.IdGenerator;
import com.mgg.exp.store.domain.deduction.entity.DeductionDetail;
import com.mgg.exp.store.domain.deduction.event.InventoryDeductedEvent;
import com.mgg.exp.store.domain.deduction.repository.DeductionDetailRepository;
import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import com.mgg.exp.store.domain.deduction.valueobject.DeductionStatus;
import com.mgg.exp.store.domain.gateway.EmergencyDegradeGateway;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.repository.InventoryRepository;
import com.mgg.exp.store.domain.inventory.valueobject.OrderId;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.domain.routing.service.RoutingDomainService;
import com.mgg.exp.store.domain.routing.valueobject.RouteResolveResult;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import com.mgg.exp.store.infrastructure.event.DomainEventPublisherImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryDeductAppServiceImpl implements InventoryDeductAppService {

    private final InventoryRepository inventoryRepository;
    private final DeductionDetailRepository deductionDetailRepository;
    private final RedisBucketGateway redisBucketGateway;
    private final EmergencyDegradeGateway emergencyDegradeGateway;
    private final RoutingDomainService routingDomainService;
    private final DomainEventPublisherImpl eventPublisher;
    private final StoreProperties storeProperties;

    private final Random random = new Random();

    @Override
    public DeductResult deduct(Long skuId, Integer quantity, String orderId) {
        if (emergencyDegradeGateway.isDegradeFlagSet(skuId)) {
            log.info("emergency degrade flag set, skip Redis path, skuId: {}", skuId);
            return directDbDeduct(skuId, quantity, orderId);
        }

        RouteResolveResult route = routingDomainService.resolveActiveLock(skuId);
        if (!route.isFound()) {
            return directDbDeduct(skuId, quantity, orderId);
        }

        String lockOrderId = route.getLockOrderId();
        int bucketCount = storeProperties.getBucket().getCount();
        int maxRetries = storeProperties.getBucket().getFalloverMaxRetries();

        for (int i = 0; i < maxRetries; i++) {
            int bucketIndex = random.nextInt(bucketCount);
            int luaResult = redisBucketGateway.deduct(lockOrderId, bucketIndex, quantity);

            if (luaResult == 1 || luaResult == 2) {
                try {
                    String detailId = insertDeductionDetail(skuId, quantity, orderId,
                            lockOrderId, bucketIndex, DeductPath.MERGE_BUCKETS);
                    DeductResult result = DeductResult.success(detailId, luaResult);

                    if (luaResult == 2) {
                        log.info("bucket exhausted, triggering async merge, lockOrderId: {}",
                                lockOrderId);
                        eventPublisher.publish(new InventoryDeductedEvent(
                                detailId, skuId, quantity, lockOrderId, 2));
                    }
                    return result;
                } catch (DuplicateKeyException e) {
                    log.warn("deduction idempotent hit, orderId: {}, skuId: {}", orderId, skuId);
                    redisBucketGateway.incrRefund(lockOrderId, bucketIndex, quantity);
                    return DeductResult.success(null, 1);
                }
            }
        }

        log.info("all buckets insufficient, degrade to DB, skuId: {}", skuId);
        return directDbDeduct(skuId, quantity, orderId);
    }

    @Transactional
    protected DeductResult directDbDeduct(Long skuId, Integer quantity, String orderId) {
        SkuId skuIdVo = new SkuId(skuId);
        int updated = inventoryRepository.directDeduct(skuIdVo, quantity);
        if (updated == 0) {
            throw new InsufficientStockException();
        }

        try {
            String detailId = insertDeductionDetail(skuId, quantity, orderId,
                    null, null, DeductPath.DIRECT_DB);
            return DeductResult.degraded(detailId);
        } catch (DuplicateKeyException e) {
            log.warn("deduction idempotent hit (DB path), orderId: {}, skuId: {}",
                    orderId, skuId);
            return DeductResult.degraded(null);
        }
    }

    private String insertDeductionDetail(Long skuId, Integer quantity, String orderId,
                                          String lockOrderId, Integer bucketIndex,
                                          DeductPath deductPath) {
        String detailId = IdGenerator.nextIdStr();
        DeductionDetail detail = new DeductionDetail();
        detail.setId(detailId);
        detail.setSkuId(new SkuId(skuId));
        detail.setQuantity(Quantity.of(quantity));
        detail.setDeductPath(deductPath);
        detail.setBucketIndex(bucketIndex);
        detail.setStatus(DeductionStatus.MERGED);
        detail.setOrderId(new OrderId(orderId));
        detail.setLockOrderId(lockOrderId);
        deductionDetailRepository.save(detail);
        return detailId;
    }
}
