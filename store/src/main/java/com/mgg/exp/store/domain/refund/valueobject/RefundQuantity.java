package com.mgg.exp.store.domain.refund.valueobject;

import com.mgg.exp.store.domain.inventory.valueobject.Quantity;

public record RefundQuantity(Quantity value) {
    public RefundQuantity {
        if (value == null || value.isZero()) {
            throw new IllegalArgumentException("refundQuantity must be positive");
        }
    }
}
