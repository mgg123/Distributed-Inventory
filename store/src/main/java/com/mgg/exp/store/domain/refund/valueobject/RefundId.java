package com.mgg.exp.store.domain.refund.valueobject;

public record RefundId(String value) {
    public RefundId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("refundId must not be blank");
        }
    }
}
