package com.mgg.exp.store.domain.inventory.valueobject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantityTest {

    @Test
    void shouldCreateQuantityWithPositiveValue() {
        Quantity q = Quantity.of(100);
        assertEquals(100, q.getValue());
    }

    @Test
    void shouldThrowWhenNegative() {
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(-1));
    }

    @Test
    void shouldCreateZero() {
        Quantity q = Quantity.zero();
        assertEquals(0, q.getValue());
    }

    @Test
    void shouldAdd() {
        Quantity a = Quantity.of(10);
        Quantity b = Quantity.of(20);
        Quantity result = a.add(b);
        assertEquals(30, result.getValue());
    }

    @Test
    void shouldSubtract() {
        Quantity a = Quantity.of(30);
        Quantity b = Quantity.of(10);
        Quantity result = a.subtract(b);
        assertEquals(20, result.getValue());
    }

    @Test
    void shouldCompare() {
        Quantity a = Quantity.of(10);
        Quantity b = Quantity.of(20);
        assertTrue(a.isLessThan(b));
        assertFalse(b.isLessThan(a));
        assertTrue(a.isLessThanOrEqual(b));
    }

    @Test
    void shouldCheckZero() {
        assertTrue(Quantity.zero().isZero());
        assertFalse(Quantity.of(1).isZero());
    }
}
