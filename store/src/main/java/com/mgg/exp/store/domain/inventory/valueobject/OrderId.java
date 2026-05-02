package com.mgg.exp.store.domain.inventory.valueobject;

public record OrderId(String value) {
    public OrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
    }
}
