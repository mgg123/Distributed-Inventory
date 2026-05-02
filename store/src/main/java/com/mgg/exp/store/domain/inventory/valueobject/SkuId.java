package com.mgg.exp.store.domain.inventory.valueobject;

public record SkuId(Long value) {
    public SkuId {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("skuId must be positive");
        }
    }
}
