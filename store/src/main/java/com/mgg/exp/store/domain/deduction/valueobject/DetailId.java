package com.mgg.exp.store.domain.deduction.valueobject;

public record DetailId(String value) {
    public DetailId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("detailId must not be blank");
        }
    }
}
