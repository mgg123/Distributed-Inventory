package com.mgg.exp.store.integration;

import com.mgg.exp.store.BaseIntegrationTest;
import com.mgg.exp.store.app.service.InventoryDeductAppService;
import com.mgg.exp.store.app.service.InventoryLockAppService;
import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.app.service.InventoryRefundAppService;
import com.mgg.exp.store.common.exception.InsufficientStockException;
import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import com.mgg.exp.store.domain.deduction.valueobject.MergeResult;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.refund.valueobject.RefundResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeductMergeRefundTest extends BaseIntegrationTest {

    @Autowired
    private InventoryLockAppService lockAppService;

    @Autowired
    private InventoryDeductAppService deductAppService;

    @Autowired
    private InventoryMergeAppService mergeAppService;

    @Autowired
    private InventoryRefundAppService refundAppService;

    @Nested
    @DisplayName("5 Redis分桶扣减模块测试")
    class DeductModuleTest {

        @Test
        @DisplayName("DEDUCT-FUNC-001: Redis分桶扣减成功")
        void testRedisBucketDeduct() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "deduct-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult result = deductAppService.deduct(10001L, 10, "order-deduct-001");

            assertTrue(result.isSuccess());
            assertNotNull(result.getDetailId());
            assertTrue(result.getLuaResult() == 1 || result.getLuaResult() == 2);
        }

        @Test
        @DisplayName("DEDUCT-FUNC-002: 扣减幂等-相同orderId+skuId")
        void testDeductIdempotent() {
            insertInventory(10001L, 20000, 0, 0, 0);
            lockAppService.lockInventory(10001L, 10000, "deduct-idem-001", 0.1);

            DeductResult first = deductAppService.deduct(10001L, 10, "order-idem-001");
            assertTrue(first.isSuccess());

            DeductResult second = deductAppService.deduct(10001L, 10, "order-idem-001");
            assertTrue(second.isSuccess());
        }

        @Test
        @DisplayName("DEDUCT-FUNC-003: DB降级扣减-无活跃lockOrder")
        void testDbDegradeDeduct() {
            insertInventory(10001L, 20000, 0, 0, 0);

            DeductResult result = deductAppService.deduct(10001L, 10, "order-db-001");

            assertTrue(result.isSuccess());
            assertEquals(-1, result.getLuaResult());

            var row = jdbcTemplate.queryForMap("SELECT sq, wq FROM inventory WHERE id = 10001");
            assertEquals(19990, row.get("sq"));
            assertEquals(10, row.get("wq"));
        }

        @Test
        @DisplayName("DEDUCT-EXCP-001: 库存不足-扣减失败")
        void testInsufficientStock() {
            insertInventory(10001L, 5, 0, 0, 0);

            org.junit.jupiter.api.Assertions.assertThrows(InsufficientStockException.class,
                    () -> deductAppService.deduct(10001L, 10, "order-insuf-001"));
        }
    }

    @Nested
    @DisplayName("7 合并提交模块测试")
    class MergeModuleTest {

        @Test
        @DisplayName("MERGE-FUNC-001: 正常合并提交-先标记后计算")
        void testMergeCommit() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "merge-001", 0.1);
            assertTrue(lockResult.isSuccess());

            deductAppService.deduct(10001L, 10, "order-merge-001");

            MergeResult result = mergeAppService.mergeCommit(lockResult.getLockOrderId());

            assertTrue(result.isSuccess());

            var row = jdbcTemplate.queryForMap("SELECT sq, wq, lq FROM inventory WHERE id = 10001");
            assertEquals(19990, row.get("sq"));
            assertEquals(10, row.get("wq"));
            assertEquals(0, row.get("lq"));

            var orderRow = jdbcTemplate.queryForMap(
                    "SELECT status FROM lock_inventory_order WHERE id = ?",
                    lockResult.getLockOrderId());
            assertEquals("ARCHIVED", orderRow.get("status"));
        }

        @Test
        @DisplayName("MERGE-FUNC-002: 无PENDING明细-跳过合并")
        void testMergeNoPending() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "merge-nopending-001", 0.1);
            assertTrue(lockResult.isSuccess());

            MergeResult result = mergeAppService.mergeCommit(lockResult.getLockOrderId());

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("MERGE-CONC-001: 二次合并触发-影响0行跳过")
        void testDoubleMerge() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "merge-double-001", 0.1);
            assertTrue(lockResult.isSuccess());

            deductAppService.deduct(10001L, 10, "order-merge-double-001");

            MergeResult first = mergeAppService.mergeCommit(lockResult.getLockOrderId());
            assertTrue(first.isSuccess());

            MergeResult second = mergeAppService.mergeCommit(lockResult.getLockOrderId());
            assertTrue(second.isSuccess());

            var row = jdbcTemplate.queryForMap("SELECT sq, wq, lq FROM inventory WHERE id = 10001");
            assertEquals(19990, row.get("sq"));
            assertEquals(10, row.get("wq"));
            assertEquals(0, row.get("lq"));
        }

        @Test
        @DisplayName("MERGE-FUNC-003: 补偿合并-等同于mergeCommit")
        void testCompensateMerge() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "comp-001", 0.1);
            assertTrue(lockResult.isSuccess());

            deductAppService.deduct(10001L, 10, "order-comp-001");

            MergeResult result = mergeAppService.compensateMerge(lockResult.getLockOrderId());

            assertTrue(result.isSuccess());

            var row = jdbcTemplate.queryForMap("SELECT sq, wq, lq FROM inventory WHERE id = 10001");
            assertEquals(19990, row.get("sq"));
            assertEquals(10, row.get("wq"));
            assertEquals(0, row.get("lq"));
        }
    }

    @Nested
    @DisplayName("9 回补管理模块测试")
    class RefundModuleTest {

        @Test
        @DisplayName("REFUND-FUNC-001: 付款确认-MERGED→OCCUPIED")
        void testConfirmPayment() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "refund-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(10001L, 10, "order-refund-001");
            assertTrue(deductResult.isSuccess());

            mergeAppService.mergeCommit(lockResult.getLockOrderId());

            RefundResult result = refundAppService.confirmPayment(deductResult.getDetailId());

            assertTrue(result.isSuccess());

            var row = jdbcTemplate.queryForMap("SELECT wq, oq FROM inventory WHERE id = 10001");
            assertEquals(0, row.get("wq"));
            assertEquals(10, row.get("oq"));
        }

        @Test
        @DisplayName("REFUND-FUNC-002: 退款-OCCUPIED→REFUNDED")
        void testRefund() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "refund-002", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(10001L, 10, "order-refund-002");
            assertTrue(deductResult.isSuccess());

            mergeAppService.mergeCommit(lockResult.getLockOrderId());
            refundAppService.confirmPayment(deductResult.getDetailId());

            RefundResult result = refundAppService.refund(deductResult.getDetailId(), 10, "refund-req-001");

            assertTrue(result.isSuccess());
            assertNotNull(result.getRefundId());

            var row = jdbcTemplate.queryForMap("SELECT oq, sq FROM inventory WHERE id = 10001");
            assertEquals(0, row.get("oq"));
            assertEquals(20000, row.get("sq"));
        }

        @Test
        @DisplayName("REFUND-FUNC-003: 取消PENDING明细")
        void testCancelPending() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "cancel-pend-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(10001L, 10, "order-cancel-pend-001");
            assertTrue(deductResult.isSuccess());

            RefundResult result = refundAppService.cancel(deductResult.getDetailId());

            assertTrue(result.isSuccess());

            var detailRow = jdbcTemplate.queryForMap(
                    "SELECT status FROM deduction_detail WHERE id = ?",
                    deductResult.getDetailId());
            assertEquals("CANCELLED", detailRow.get("status"));
        }

        @Test
        @DisplayName("REFUND-FUNC-004: 取消MERGED明细")
        void testCancelMerged() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "cancel-merged-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(10001L, 10, "order-cancel-merged-001");
            assertTrue(deductResult.isSuccess());

            mergeAppService.mergeCommit(lockResult.getLockOrderId());

            RefundResult result = refundAppService.cancel(deductResult.getDetailId());

            assertTrue(result.isSuccess());

            var detailRow = jdbcTemplate.queryForMap(
                    "SELECT status FROM deduction_detail WHERE id = ?",
                    deductResult.getDetailId());
            assertEquals("CANCELLED", detailRow.get("status"));

            var invRow = jdbcTemplate.queryForMap("SELECT wq, sq FROM inventory WHERE id = 10001");
            assertEquals(0, invRow.get("wq"));
            assertEquals(20000, invRow.get("sq"));
        }

        @Test
        @DisplayName("REFUND-FUNC-005: 退款幂等-相同refundRequestId")
        void testRefundIdempotent() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "refund-idem-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(10001L, 10, "order-refund-idem-001");
            assertTrue(deductResult.isSuccess());

            mergeAppService.mergeCommit(lockResult.getLockOrderId());
            refundAppService.confirmPayment(deductResult.getDetailId());

            RefundResult first = refundAppService.refund(deductResult.getDetailId(), 10, "refund-req-idem-001");
            assertTrue(first.isSuccess());

            RefundResult second = refundAppService.refund(deductResult.getDetailId(), 10, "refund-req-idem-001");
            assertTrue(second.isSuccess());
        }

        @Test
        @DisplayName("REFUND-EXCP-001: 非OCCUPIED状态退款失败")
        void testRefundNotOccupied() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "refund-notocc-001", 0.1);
            assertTrue(lockResult.isSuccess());

            DeductResult deductResult = deductAppService.deduct(10001L, 10, "order-refund-notocc-001");
            assertTrue(deductResult.isSuccess());

            RefundResult result = refundAppService.refund(deductResult.getDetailId(), 10, "refund-req-notocc-001");
            assertTrue(!result.isSuccess() || result.getErrorCode() != null);
        }
    }

    @Nested
    @DisplayName("10 紧急降级模块测试")
    class EmergencyTest {

        @Test
        @DisplayName("EMER-FUNC-001: 紧急解锁-force=false触发合并提交")
        void testEmergencyUnlockNonForce() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "emer-001", 0.1);
            assertTrue(lockResult.isSuccess());

            deductAppService.deduct(10001L, 10, "order-emer-001");

            com.mgg.exp.store.app.service.EmergencyAppService emergencyAppService = applicationContext
                    .getBean(com.mgg.exp.store.app.service.EmergencyAppService.class);
            emergencyAppService.emergencyUnlock(10001L, false);

            var row = jdbcTemplate.queryForMap("SELECT lq FROM inventory WHERE id = 10001");
            assertEquals(0, row.get("lq"));
        }

        @Test
        @DisplayName("EMER-FUNC-002: 紧急解锁-force=true强制重置lq")
        void testEmergencyUnlockForce() {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "emer-force-001", 0.1);
            assertTrue(lockResult.isSuccess());

            com.mgg.exp.store.app.service.EmergencyAppService emergencyAppService = applicationContext
                    .getBean(com.mgg.exp.store.app.service.EmergencyAppService.class);
            emergencyAppService.emergencyUnlock(10001L, true);

            var row = jdbcTemplate.queryForMap("SELECT lq FROM inventory WHERE id = 10001");
            assertEquals(0, row.get("lq"));

            var orderRow = jdbcTemplate.queryForMap(
                    "SELECT status FROM lock_inventory_order WHERE id = ?",
                    lockResult.getLockOrderId());
            assertEquals("ARCHIVED", orderRow.get("status"));
        }
    }

    @Autowired
    private ApplicationContext applicationContext;
}
