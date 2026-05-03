package com.mgg.exp.store.app.service;

import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import com.mgg.exp.store.domain.deduction.valueobject.MergeResult;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.refund.valueobject.RefundResult;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventoryIntegrationTest {

    @Autowired
    private InventoryLockAppService lockAppService;

    @Autowired
    private InventoryDeductAppService deductAppService;

    @Autowired
    private InventoryMergeAppService mergeAppService;

    @Autowired
    private InventoryRefundAppService refundAppService;

    private static String lockOrderId;
    private static String detailId;

    @Test
    @Order(1)
    void testLockInventory() {
        LockResult result = lockAppService.lockInventory(
                10001L, 10000, "test-lock-idempotent-001", 0.1);

        assertTrue(result.isSuccess());
        assertNotNull(result.getLockOrderId());
        assertNotNull(result.getActualLockQuantity());
        assertTrue(result.getActualLockQuantity().getValue() > 0);

        lockOrderId = result.getLockOrderId();
    }

    @Test
    @Order(2)
    void testLockInventoryIdempotent() {
        LockResult result = lockAppService.lockInventory(
                10001L, 10000, "test-lock-idempotent-001", 0.1);

        assertTrue(result.isSuccess());
        assertEquals(lockOrderId, result.getLockOrderId());
    }

    @Test
    @Order(3)
    void testDeductInventory() {
        DeductResult result = deductAppService.deduct(
                10001L, 10, "test-order-001");

        assertTrue(result.isSuccess());
        assertNotNull(result.getDetailId());

        detailId = result.getDetailId();
    }

    @Test
    @Order(4)
    void testDeductInventoryIdempotent() {
        DeductResult result = deductAppService.deduct(
                10001L, 10, "test-order-001");

        assertTrue(result.isSuccess());
    }

    @Test
    @Order(5)
    void testMergeCommit() {
        MergeResult result = mergeAppService.mergeCommit(lockOrderId);

        assertTrue(result.isSuccess());
    }

    @Test
    @Order(6)
    void testConfirmPayment() {
        RefundResult result = refundAppService.confirmPayment(detailId);

        assertTrue(result.isSuccess());
    }

    @Test
    @Order(7)
    void testRefund() {
        RefundResult result = refundAppService.refund(detailId, 10, "test-refund-req-001");

        assertTrue(result.isSuccess());
        assertNotNull(result.getRefundId());
    }
}
