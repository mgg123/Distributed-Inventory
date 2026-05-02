package com.mgg.exp.store.domain.inventory.event;

public class AutoLockEvent {

    private final Long skuId;
    private final String triggerReason;

    public AutoLockEvent(Long skuId, String triggerReason) {
        this.skuId = skuId;
        this.triggerReason = triggerReason;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getTriggerReason() {
        return triggerReason;
    }
}
