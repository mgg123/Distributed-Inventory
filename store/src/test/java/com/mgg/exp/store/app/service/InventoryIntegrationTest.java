package com.mgg.exp.store.app.service;

import com.mgg.exp.store.BaseIntegrationTest;
import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import com.mgg.exp.store.domain.deduction.valueobject.MergeResult;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.refund.valueobject.RefundResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InventoryLockAppService lockAppService;

    @Autowired
    private InventoryDeductAppService deductAppService;

    @Autowired
    private InventoryMergeAppService mergeAppService;

    @Autowired
    private InventoryRefundAppService refundAppService;

    @Nested
    @DisplayName("锁库存集成测试")
    class LockTest {

        @Test
        @DisplayName("锁库存-正常流程")
        void testLockInventory() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult result = lockAppService.lockInventory(
                    10001L, 10000, "test-lock-001", 0.1);

            assertTrue(result.isSuccess());
            assertNotNull(result.getLockOrderId());
            assertNotNull(result.getActualLockQuantity());
            assertTrue(result.getActualLockQuantity().getValue() > 0);
        }

        @Test
        @DisplayName("锁库存-幂等命中")
        void testLockInventoryIdempotent() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult first = lockAppService.lockInventory(
                    10001L, 10000, "test-lock-idem-001", 0.1);
            assertTrue(first.isSuccess());

            LockResult second = lockAppService.lockInventory(
                    10001L, 10000, "test-lock-idem-001", 0.1);
            assertTrue(second.isSuccess());
            assertEquals(first.getLockOrderId(), second.getLockOrderId());
        }
    }

    @Nested
    @DisplayName("扣减集成测试")
    class DeductTest {

        @Test
        @DisplayName("扣减-正常流程")
        void testDeductInventory() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(
                    10001L, 10000, "test-deduct-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult result = deductAppService.deduct(
                    10001L, 10, "test-order-deduct-001");

            assertTrue(result.isSuccess());
            assertNotNull(result.getDetailId());
        }

        @Test
        @DisplayName("扣减-幂等命中")
        void testDeductInventoryIdempotent() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(
                    10001L, 10000, "test-deduct-idem-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult first = deductAppService.deduct(
                    10001L, 10, "test-order-idem-001");
            assertTrue(first.isSuccess());

            DeductResult second = deductAppService.deduct(
                    10001L, 10, "test-order-idem-001");
            assertTrue(second.isSuccess());
        }
    }

    @Nested
    @DisplayName("合并+确认+退款集成测试")
    class MergeConfirmRefundTest {

        @Test
        @DisplayName("合并提交-正常流程")
        void testMergeCommit() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(
                    10001L, 10000, "test-merge-001", 0.1);
            assertTrue(lockResult.isSuccess());

            deductAppService.deduct(10001L, 10, "test-order-merge-001");

            MergeResult result = mergeAppService.mergeCommit(lockResult.getLockOrderId());
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("付款确认-正常流程")
        void testConfirmPayment() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(
                    10001L, 10000, "test-confirm-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(
                    10001L, 10, "test-order-confirm-001");
            assertTrue(deductResult.isSuccess());

            mergeAppService.mergeCommit(lockResult.getLockOrderId());

            RefundResult result = refundAppService.confirmPayment(deductResult.getDetailId());
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("退款-正常流程")
        void testRefund() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(
                    10001L, 10000, "test-refund-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(
                    10001L, 10, "test-order-refund-001");
            assertTrue(deductResult.isSuccess());

            mergeAppService.mergeCommit(lockResult.getLockOrderId());
            refundAppService.confirmPayment(deductResult.getDetailId());

            RefundResult result = refundAppService.refund(
                    deductResult.getDetailId(), 10, "test-refund-req-001");

            assertTrue(result.isSuccess());
            assertNotNull(result.getRefundId());
        }
    }
}
