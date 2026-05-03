package com.mgg.exp.store.app.scheduler;

import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.domain.deduction.entity.DeductionDetail;
import com.mgg.exp.store.domain.deduction.repository.DeductionDetailRepository;
import com.mgg.exp.store.domain.deduction.valueobject.DeductionStatus;
import com.mgg.exp.store.domain.gateway.ActiveLockRouterGateway;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompensateTask {

    private final LockOrderRepository lockOrderRepository;
    private final DeductionDetailRepository deductionDetailRepository;
    private final InventoryMergeAppService mergeAppService;
    private final RedisBucketGateway redisBucketGateway;
    private final ActiveLockRouterGateway routerGateway;
    private final StoreProperties storeProperties;

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void compensateOrphanPendingDetails() {
        List<LockInventoryOrder> expiredOrders = lockOrderRepository.findExpiredActive(
                LocalDateTime.now());

        for (LockInventoryOrder order : expiredOrders) {
            try {
                List<DeductionDetail> pendingDetails = deductionDetailRepository
                        .findByLockOrderIdAndStatus(order.getId().value(),
                                DeductionStatus.PENDING.name());

                if (!pendingDetails.isEmpty()) {
                    log.info("found {} orphan PENDING details for ARCHIVED lockOrder: {}, " +
                            "triggering merge commit", pendingDetails.size(),
                            order.getId().value());
                    mergeAppService.mergeCommit(order.getId().value());
                }
            } catch (Exception e) {
                log.error("compensate orphan pending details failed, lockOrderId: {}",
                        order.getId().value(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void compensateCrashRecovery() {
        List<LockInventoryOrder> allOrders = lockOrderRepository.findExpiredActive(
                LocalDateTime.now().plusYears(10));

        for (LockInventoryOrder order : allOrders) {
            if (order.isMergeCompleted()) {
                continue;
            }

            try {
                boolean metaValid = redisBucketGateway.isBucketMetaValid(order.getId().value());
                if (metaValid) {
                    log.info("crash recovery: cleaning up buckets for uncompleted merge, " +
                            "lockOrderId: {}", order.getId().value());
                    redisBucketGateway.cleanupBuckets(order.getId().value(),
                            storeProperties.getBucket().getCount());
                }
            } catch (Exception e) {
                log.error("crash recovery cleanup failed, lockOrderId: {}",
                        order.getId().value(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void compensateRouteCache() {
        List<LockInventoryOrder> activeOrders = lockOrderRepository.findExpiredActive(
                LocalDateTime.now().plusYears(10));

        for (LockInventoryOrder order : activeOrders) {
            if (!order.isActive()) {
                continue;
            }

            try {
                String cachedLockOrderId = routerGateway.getActiveLock(order.getSkuId().value());
                if (cachedLockOrderId == null || !cachedLockOrderId.equals(order.getId().value())) {
                    log.info("route cache compensation: updating active lock route, " +
                            "skuId: {}, lockOrderId: {}", order.getSkuId().value(),
                            order.getId().value());
                    routerGateway.addToHistory(order.getSkuId().value(), order.getId().value());
                    routerGateway.setActiveLock(order.getSkuId().value(), order.getId().value());
                }
            } catch (Exception e) {
                log.error("route cache compensation failed, skuId: {}, lockOrderId: {}",
                        order.getSkuId().value(), order.getId().value(), e);
            }
        }
    }
}
