package com.mgg.exp.store.domain.deduction.valueobject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeductionStatusTest {

    @Test
    void shouldAllowPendingToMerged() {
        assertTrue(DeductionStatus.PENDING.canTransitTo(DeductionStatus.MERGED));
    }

    @Test
    void shouldAllowPendingToCancelled() {
        assertTrue(DeductionStatus.PENDING.canTransitTo(DeductionStatus.CANCELLED));
    }

    @Test
    void shouldAllowMergedToOccupied() {
        assertTrue(DeductionStatus.MERGED.canTransitTo(DeductionStatus.OCCUPIED));
    }

    @Test
    void shouldAllowMergedToCancelled() {
        assertTrue(DeductionStatus.MERGED.canTransitTo(DeductionStatus.CANCELLED));
    }

    @Test
    void shouldAllowOccupiedToRefunded() {
        assertTrue(DeductionStatus.OCCUPIED.canTransitTo(DeductionStatus.REFUNDED));
    }

    @Test
    void shouldNotAllowOccupiedToCancelled() {
        assertFalse(DeductionStatus.OCCUPIED.canTransitTo(DeductionStatus.CANCELLED));
    }

    @Test
    void shouldNotAllowRefundedTransition() {
        assertFalse(DeductionStatus.REFUNDED.canTransitTo(DeductionStatus.PENDING));
    }

    @Test
    void shouldThrowOnInvalidTransition() {
        assertThrows(IllegalStateException.class,
                () -> DeductionStatus.OCCUPIED.checkTransition(DeductionStatus.PENDING));
    }
}
