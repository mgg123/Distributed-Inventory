package com.mgg.exp.store.domain.deduction.event;

public class MergeCommittedEvent {

    private final String lockOrderId;
    private final Long skuId;
    private final int netDeduction;

    public MergeCommittedEvent(String lockOrderId, Long skuId, int netDeduction) {
        this.lockOrderId = lockOrderId;
        this.skuId = skuId;
        this.netDeduction = netDeduction;
    }

    public String getLockOrderId() {
        return lockOrderId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getNetDeduction() {
        return netDeduction;
    }
}
