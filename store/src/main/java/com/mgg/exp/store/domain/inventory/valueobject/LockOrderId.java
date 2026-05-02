package com.mgg.exp.store.domain.inventory.valueobject;

public record LockOrderId(String value) {
    public LockOrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("lockOrderId must not be blank");
        }
    }
}
