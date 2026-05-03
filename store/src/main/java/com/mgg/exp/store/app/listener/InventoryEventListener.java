package com.mgg.exp.store.app.listener;

import com.mgg.exp.store.app.service.AutoLockAppService;
import com.mgg.exp.store.app.service.EmergencyAppService;
import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.domain.deduction.event.InventoryDeductedEvent;
import com.mgg.exp.store.domain.deduction.event.MergeCommittedEvent;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.event.AutoLockEvent;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private final AutoLockAppService autoLockAppService;
    private final InventoryMergeAppService mergeAppService;
    private final EmergencyAppService emergencyAppService;
    private final RedisBucketGateway redisBucketGateway;
    private final StoreProperties storeProperties;

    @Async
    @EventListener
    public void onInventoryDeducted(InventoryDeductedEvent event) {
        log.info("received InventoryDeductedEvent, detailId: {}, skuId: {}, luaResult: {}",
                event.getDetailId(), event.getSkuId(), event.getLuaResult());

        if (event.isBucketExhausted()) {
            try {
                mergeAppService.mergeCommit(event.getLockOrderId());
            } catch (Exception e) {
                log.error("async merge commit failed after bucket exhausted, lockOrderId: {}",
                        event.getLockOrderId(), e);
            }
        }

        checkAutoLockTrigger(event.getSkuId(), event.getLockOrderId());
    }

    @Async
    @EventListener
    public void onMergeCommitted(MergeCommittedEvent event) {
        log.info("received MergeCommittedEvent, lockOrderId: {}, skuId: {}, netDeduction: {}",
                event.getLockOrderId(), event.getSkuId(), event.getNetDeduction());

        checkAutoLockTrigger(event.getSkuId(), null);
    }

    @Async
    @EventListener
    public void onAutoLockEvent(AutoLockEvent event) {
        log.info("received AutoLockEvent, skuId: {}, reason: {}",
                event.getSkuId(), event.getTriggerReason());
        try {
            autoLockAppService.triggerAutoLock(event.getSkuId(), event.getTriggerReason());
        } catch (Exception e) {
            log.error("auto lock event handling failed, skuId: {}", event.getSkuId(), e);
        }
    }

    private void checkAutoLockTrigger(Long skuId, String lockOrderId) {
        if (!storeProperties.getAutoLock().isEnabled()) {
            return;
        }

        String effectiveLockOrderId = lockOrderId;
        if (effectiveLockOrderId == null) {
            int totalRemaining = redisBucketGateway.getTotalRemaining(null);
            if (totalRemaining < 0) {
                return;
            }
        }

        if (effectiveLockOrderId != null) {
            int totalRemaining = redisBucketGateway.getTotalRemaining(effectiveLockOrderId);
            if (totalRemaining < 0) {
                return;
            }

            double triggerRatio = storeProperties.getAutoLock().getTriggerRatio();
            int minLockQuantity = storeProperties.getAutoLock().getMinLockQuantity();
            if (totalRemaining <= triggerRatio * minLockQuantity) {
                autoLockAppService.triggerAutoLock(skuId, "LOW_REMAINING");
            }
        }
    }
}
