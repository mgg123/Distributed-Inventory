package com.mgg.exp.store.app.scheduler;

import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
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
public class MergeSchedulerTask {

    private final LockOrderRepository lockOrderRepository;
    private final InventoryMergeAppService mergeAppService;
    private final RedisBucketGateway redisBucketGateway;
    private final StoreProperties storeProperties;

    @Scheduled(fixedDelayString = "${store.merge.delay-ms:1000}")
    public void scheduleMerge() {
        List<LockInventoryOrder> activeOrders = lockOrderRepository.findExpiredActive(
                LocalDateTime.now().minusSeconds(1));

        for (LockInventoryOrder order : activeOrders) {
            try {
                if (shouldTriggerMerge(order)) {
                    mergeAppService.mergeCommit(order.getId().value());
                    log.info("scheduled merge commit success, lockOrderId: {}",
                            order.getId().value());
                }
            } catch (Exception e) {
                log.error("scheduled merge commit failed, lockOrderId: {}",
                        order.getId().value(), e);
            }
        }
    }

    private boolean shouldTriggerMerge(LockInventoryOrder order) {
        if (order.isExpired()) {
            log.info("lock order expired, trigger merge, lockOrderId: {}",
                    order.getId().value());
            return true;
        }

        int totalRemaining = redisBucketGateway.getTotalRemaining(order.getId().value());
        if (totalRemaining == 0) {
            log.info("bucket exhausted, trigger merge, lockOrderId: {}",
                    order.getId().value());
            return true;
        }

        if (totalRemaining > 0) {
            double triggerRatio = storeProperties.getAutoLock().getTriggerRatio();
            int lockQuantity = order.getLockQuantity().getValue();
            if (totalRemaining <= triggerRatio * lockQuantity) {
                log.info("low remaining: {}, trigger merge, lockOrderId: {}",
                        totalRemaining, order.getId().value());
                return true;
            }
        }

        return false;
    }
}
