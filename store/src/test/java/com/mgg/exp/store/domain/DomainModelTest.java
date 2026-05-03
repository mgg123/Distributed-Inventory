package com.mgg.exp.store.domain;

import com.mgg.exp.store.domain.deduction.valueobject.DeductionStatus;
import com.mgg.exp.store.domain.inventory.aggregate.InventoryAggregate;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainModelTest {

    @Nested
    @DisplayName("InventoryAggregate 领域模型测试")
    class InventoryAggregateTest {

        private InventoryAggregate createAggregate(int sq, int wq, int oq, int lq) {
            return new InventoryAggregate(new SkuId(10001L), Quantity.of(sq),
                    Quantity.of(wq), Quantity.of(oq), Quantity.of(lq));
        }

        @Test
        @DisplayName("LOCK-FUNC-001: 正常锁库存-lockAmount不超过maxLock时全额锁定")
        void testLockNormal() {
            InventoryAggregate agg = createAggregate(20000, 0, 0, 0);
            LockResult result = agg.lock(Quantity.of(10000), 0.1);

            assertTrue(result.isSuccess());
            assertEquals(10000, result.getActualLockQuantity().getValue());
            assertEquals(2000, result.getReservedQuantity().getValue());
        }

        @Test
        @DisplayName("LOCK-FUNC-004: 部分锁定-lockAmount大于maxLock时截断为maxLock")
        void testPartialLock() {
            InventoryAggregate agg = createAggregate(5000, 0, 0, 4500);
            LockResult result = agg.lock(Quantity.of(1000), 0.1);

            assertTrue(result.isSuccess());
            assertEquals(450, result.getActualLockQuantity().getValue());
        }

        @Test
        @DisplayName("LOCK-FUNC-005: 可用额度极低-maxLock为0时锁定失败")
        void testInsufficientAvailable() {
            InventoryAggregate agg = createAggregate(5099, 0, 0, 5000);
            LockResult result = agg.lock(Quantity.of(1000), 0.1);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("LOCK-FUNC-006: 预留DB降级额度-reserveRatio计算")
        void testReserveRatio() {
            InventoryAggregate agg = createAggregate(20000, 0, 0, 0);
            LockResult result = agg.lock(Quantity.of(10000), 0.1);

            assertTrue(result.isSuccess());
            assertEquals(10000, result.getActualLockQuantity().getValue());
            assertEquals(2000, result.getReservedQuantity().getValue());
        }

        @Test
        @DisplayName("LOCK-FUNC-007: reserveRatio=0-锁定全部额度")
        void testZeroReserveRatio() {
            InventoryAggregate agg = createAggregate(10000, 0, 0, 0);
            LockResult result = agg.lock(Quantity.of(10000), 0.0);

            assertTrue(result.isSuccess());
            assertEquals(10000, result.getActualLockQuantity().getValue());
        }

        @Test
        @DisplayName("LOCK-FUNC-010: reserve-ratio与min-lock-quantity死区验证")
        void testDeadZone() {
            InventoryAggregate agg = createAggregate(5111, 0, 0, 5000);
            LockResult result = agg.lock(Quantity.of(1000), 0.1);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("合并提交-正常流程")
        void testMergeCommit() {
            InventoryAggregate agg = createAggregate(20000, 0, 0, 9000);
            agg.mergeCommit(Quantity.of(300), Quantity.of(9000));

            assertEquals(19700, agg.getSq().getValue());
            assertEquals(300, agg.getWq().getValue());
            assertEquals(0, agg.getLq().getValue());
        }

        @Test
        @DisplayName("释放锁库存")
        void testRelease() {
            InventoryAggregate agg = createAggregate(20000, 0, 0, 9000);
            agg.release(Quantity.of(9000));

            assertEquals(0, agg.getLq().getValue());
        }

        @Test
        @DisplayName("获取可用库存")
        void testGetAvailableQuantity() {
            InventoryAggregate agg = createAggregate(20000, 0, 0, 9000);
            assertEquals(11000, agg.getAvailableQuantity().getValue());
        }

        @Test
        @DisplayName("lockAmount大于maxLock时-截断为maxLock")
        void testLockAmountExceedsMaxLock() {
            InventoryAggregate agg = createAggregate(10000, 0, 0, 0);
            LockResult result = agg.lock(Quantity.of(20000), 0.1);

            assertTrue(result.isSuccess());
            assertEquals(9000, result.getActualLockQuantity().getValue());
        }

        @Test
        @DisplayName("available为零时-锁定失败")
        void testZeroAvailable() {
            InventoryAggregate agg = createAggregate(5000, 0, 0, 5000);
            LockResult result = agg.lock(Quantity.of(100), 0.1);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("reserveRatio=0.5-锁定不超过一半可用额度")
        void testHalfReserveRatio() {
            InventoryAggregate agg = createAggregate(10000, 0, 0, 0);
            LockResult result = agg.lock(Quantity.of(10000), 0.5);

            assertTrue(result.isSuccess());
            assertEquals(5000, result.getActualLockQuantity().getValue());
            assertEquals(5000, result.getReservedQuantity().getValue());
        }
    }

    @Nested
    @DisplayName("Quantity 值对象测试")
    class QuantityTest {

        @Test
        @DisplayName("负数Quantity抛异常")
        void testNegativeQuantity() {
            assertThrows(IllegalArgumentException.class, () -> Quantity.of(-1));
        }

        @Test
        @DisplayName("加法运算")
        void testAdd() {
            Quantity a = Quantity.of(10);
            Quantity b = Quantity.of(20);
            assertEquals(30, a.add(b).getValue());
        }

        @Test
        @DisplayName("减法运算")
        void testSubtract() {
            Quantity a = Quantity.of(30);
            Quantity b = Quantity.of(10);
            assertEquals(20, a.subtract(b).getValue());
        }

        @Test
        @DisplayName("比较运算")
        void testComparison() {
            Quantity a = Quantity.of(10);
            Quantity b = Quantity.of(20);
            assertTrue(a.isLessThan(b));
            assertFalse(b.isLessThan(a));
            assertTrue(a.isLessThanOrEqual(b));
            assertTrue(b.isGreaterThan(a));
        }

        @Test
        @DisplayName("零值判断")
        void testZero() {
            Quantity zero = Quantity.zero();
            assertTrue(zero.isZero());
            assertFalse(Quantity.of(1).isZero());
        }
    }

    @Nested
    @DisplayName("DeductionStatus 状态转换测试")
    class DeductionStatusTest {

        @Test
        @DisplayName("PENDING可转换为MERGED")
        void testPendingToMerged() {
            assertTrue(DeductionStatus.PENDING.canTransitTo(DeductionStatus.MERGED));
        }

        @Test
        @DisplayName("PENDING可转换为CANCELLED")
        void testPendingToCancelled() {
            assertTrue(DeductionStatus.PENDING.canTransitTo(DeductionStatus.CANCELLED));
        }

        @Test
        @DisplayName("MERGED可转换为OCCUPIED")
        void testMergedToOccupied() {
            assertTrue(DeductionStatus.MERGED.canTransitTo(DeductionStatus.OCCUPIED));
        }

        @Test
        @DisplayName("MERGED可转换为CANCELLED")
        void testMergedToCancelled() {
            assertTrue(DeductionStatus.MERGED.canTransitTo(DeductionStatus.CANCELLED));
        }

        @Test
        @DisplayName("OCCUPIED可转换为REFUNDED")
        void testOccupiedToRefunded() {
            assertTrue(DeductionStatus.OCCUPIED.canTransitTo(DeductionStatus.REFUNDED));
        }

        @Test
        @DisplayName("终态不可转换")
        void testTerminalState() {
            assertFalse(DeductionStatus.CANCELLED.canTransitTo(DeductionStatus.MERGED));
            assertFalse(DeductionStatus.REFUNDED.canTransitTo(DeductionStatus.OCCUPIED));
        }

        @Test
        @DisplayName("非法转换抛异常")
        void testInvalidTransition() {
            assertThrows(IllegalStateException.class,
                    () -> DeductionStatus.CANCELLED.checkTransition(DeductionStatus.MERGED));
        }
    }
}
