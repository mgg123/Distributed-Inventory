package com.mgg.exp.store.app.scheduler;

import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LockExpireTask {

    private final LockOrderRepository lockOrderRepository;
    private final InventoryMergeAppService mergeAppService;

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void checkAndReleaseExpiredLocks() {
        List<LockInventoryOrder> expiredOrders = lockOrderRepository.findExpiredActive(
                LocalDateTime.now());

        for (LockInventoryOrder order : expiredOrders) {
            try {
                log.warn("detected expired lock order, triggering merge commit, " +
                        "lockOrderId: {}, skuId: {}, expireTime: {}",
                        order.getId().value(), order.getSkuId().value(),
                        order.getExpireTime());

                mergeAppService.mergeCommit(order.getId().value());

                log.info("expired lock order merge committed, lockOrderId: {}",
                        order.getId().value());
            } catch (Exception e) {
                log.error("failed to merge commit expired lock order, lockOrderId: {}",
                        order.getId().value(), e);
            }
        }
    }
}
