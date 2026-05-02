package com.mgg.exp.store.domain.deduction.event;

public class InventoryDeductedEvent {

    private final String detailId;
    private final Long skuId;
    private final int quantity;
    private final String lockOrderId;
    private final int luaResult;

    public InventoryDeductedEvent(String detailId, Long skuId, int quantity,
                                  String lockOrderId, int luaResult) {
        this.detailId = detailId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.lockOrderId = lockOrderId;
        this.luaResult = luaResult;
    }

    public String getDetailId() {
        return detailId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getLockOrderId() {
        return lockOrderId;
    }

    public int getLuaResult() {
        return luaResult;
    }

    public boolean isBucketExhausted() {
        return luaResult == 2;
    }
}
