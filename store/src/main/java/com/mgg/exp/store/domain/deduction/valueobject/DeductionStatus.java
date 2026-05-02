package com.mgg.exp.store.domain.deduction.valueobject;

import java.util.Map;
import java.util.Set;

public enum DeductionStatus {

    PENDING,
    MERGED,
    OCCUPIED,
    CANCELLED,
    REFUNDED;

    private static final Map<DeductionStatus, Set<DeductionStatus>> TRANSITIONS = Map.of(
            PENDING, Set.of(MERGED, CANCELLED),
            MERGED, Set.of(OCCUPIED, CANCELLED),
            OCCUPIED, Set.of(REFUNDED),
            CANCELLED, Set.of(),
            REFUNDED, Set.of()
    );

    public boolean canTransitTo(DeductionStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public void checkTransition(DeductionStatus target) {
        if (!canTransitTo(target)) {
            throw new IllegalStateException(
                    "Cannot transit from " + this + " to " + target);
        }
    }
}
