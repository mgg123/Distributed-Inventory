package com.mgg.exp.store.domain.inventory.aggregate;

import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryAggregateTest {

    private InventoryAggregate aggregate;

    @BeforeEach
    void setUp() {
        aggregate = new InventoryAggregate(
                new SkuId(10001L),
                Quantity.of(10000),
                Quantity.zero(),
                Quantity.zero(),
                Quantity.zero()
        );
    }

    @Test
    void shouldLockSuccessfully() {
        var result = aggregate.lock(Quantity.of(1000), 0.1);
        assertTrue(result.isSuccess());
        assertEquals(1000, result.getActualLockQuantity().getValue());
        assertEquals(1000, aggregate.getLq().getValue());
    }

    @Test
    void shouldLockFullWhenNoReserve() {
        var result = aggregate.lock(Quantity.of(1000), 0.0);
        assertTrue(result.isSuccess());
        assertEquals(1000, result.getActualLockQuantity().getValue());
    }

    @Test
    void shouldReturnInsufficientWhenNoAvailable() {
        aggregate = new InventoryAggregate(
                new SkuId(10001L),
                Quantity.of(100),
                Quantity.zero(),
                Quantity.zero(),
                Quantity.of(100)
        );
        var result = aggregate.lock(Quantity.of(50), 0.1);
        assertFalse(result.isSuccess());
    }

    @Test
    void shouldMergeCommitCorrectly() {
        aggregate.lock(Quantity.of(1000), 0.0);
        int lockQty = aggregate.getLq().getValue();

        aggregate.mergeCommit(Quantity.of(300), Quantity.of(lockQty));

        assertEquals(9700, aggregate.getSq().getValue());
        assertEquals(300, aggregate.getWq().getValue());
        assertEquals(0, aggregate.getLq().getValue());
    }

    @Test
    void shouldCalculateAvailableQuantity() {
        aggregate.lock(Quantity.of(1000), 0.0);
        assertEquals(9000, aggregate.getAvailableQuantity().getValue());
    }
}
