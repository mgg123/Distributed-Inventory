package com.mgg.exp.store.domain.deduction.valueobject;

public record MergeBatchId(String value) {
    public MergeBatchId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mergeBatchId must not be blank");
        }
    }
}
