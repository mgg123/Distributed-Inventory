package com.mgg.exp.store.integration;

import com.mgg.exp.store.BaseIntegrationTest;
import com.mgg.exp.store.app.service.InventoryLockAppService;
import com.mgg.exp.store.common.exception.LockOrderAlreadyArchivedException;
import com.mgg.exp.store.common.exception.LockQuantityExceededException;
import com.mgg.exp.store.domain.gateway.ActiveLockRouterGateway;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockInventoryTest extends BaseIntegrationTest {

    @Autowired
    private InventoryLockAppService lockAppService;

    @Autowired
    private ActiveLockRouterGateway routerGateway;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Nested
    @DisplayName("3 锁库存管理模块测试")
    class LockModuleTest {

        @Test
        @DisplayName("LOCK-FUNC-001: 正常锁库存-完整严格时序Step 0→1→2→3")
        void testLockInventoryNormal() {
            insertInventory(10001L, 20000, 0, 0, 0);

            LockResult result = lockAppService.lockInventory(10001L, 10000, "lock-001", 0.1);

            assertTrue(result.isSuccess());
            assertNotNull(result.getLockOrderId());
            assertEquals(10000, result.getActualLockQuantity().getValue());

            var row = jdbcTemplate.queryForMap("SELECT sq, lq FROM inventory WHERE id = 10001");
            assertEquals(20000, row.get("sq"));
            assertEquals(10000, row.get("lq"));

            String activeLock = routerGateway.getActiveLock(10001L);
            assertNotNull(activeLock);
            assertEquals(result.getLockOrderId(), activeLock);

            String totalRemaining = redisTemplate.opsForValue().get(
                    "inventory:{" + result.getLockOrderId() + "}:lock:total_remaining");
            assertNotNull(totalRemaining);
            assertEquals("10000", totalRemaining);
        }

        @Test
        @DisplayName("LOCK-FUNC-002: 锁库存幂等-相同idempotentKey重复请求")
        void testLockInventoryIdempotent() {
            insertInventory(10001L, 20000, 0, 0, 0);

            LockResult first = lockAppService.lockInventory(10001L, 10000, "lock-idem-001", 0.1);
            assertTrue(first.isSuccess());
            String firstLockOrderId = first.getLockOrderId();

            LockResult second = lockAppService.lockInventory(10001L, 10000, "lock-idem-001", 0.1);
            assertTrue(second.isSuccess());
            assertEquals(firstLockOrderId, second.getLockOrderId());

            var row = jdbcTemplate.queryForMap("SELECT lq FROM inventory WHERE id = 10001");
            assertEquals(10000, row.get("lq"));
        }

        @Test
        @DisplayName("LOCK-FUNC-003: ARCHIVED状态幂等冲突")
        void testLockInventoryArchivedConflict() {
            insertInventory(10001L, 20000, 0, 0, 0);

            LockResult first = lockAppService.lockInventory(10001L, 10000, "lock-arch-001", 0.1);
            assertTrue(first.isSuccess());

            jdbcTemplate.update("UPDATE lock_inventory_order SET status = 'ARCHIVED' WHERE id = ?",
                    first.getLockOrderId());

            org.junit.jupiter.api.Assertions.assertThrows(LockOrderAlreadyArchivedException.class,
                    () -> lockAppService.lockInventory(10001L, 10000, "lock-arch-001", 0.1));
        }

        @Test
        @DisplayName("LOCK-FUNC-004: 部分锁定-sq-lq不足lockQuantity")
        void testPartialLock() {
            insertInventory(10001L, 5000, 0, 0, 4500);

            LockResult result = lockAppService.lockInventory(10001L, 1000, "lock-partial-001", 0.1);

            assertTrue(result.isSuccess());
            assertEquals(450, result.getActualLockQuantity().getValue());
        }

        @Test
        @DisplayName("LOCK-FUNC-005: 可用额度为零-抛出LockQuantityExceededException")
        void testInsufficientAvailable() {
            insertInventory(10001L, 5000, 0, 0, 5000);

            org.junit.jupiter.api.Assertions.assertThrows(LockQuantityExceededException.class,
                    () -> lockAppService.lockInventory(10001L, 1000, "lock-insuf-001", 0.1));
        }

        @Test
        @DisplayName("LOCK-FUNC-006: 预留DB降级额度-reserveRatio计算")
        void testReserveRatio() {
            insertInventory(10001L, 20000, 0, 0, 0);

            LockResult result = lockAppService.lockInventory(10001L, 10000, "lock-reserve-001", 0.1);

            assertTrue(result.isSuccess());
            assertEquals(10000, result.getActualLockQuantity().getValue());
            assertEquals(2000, result.getReservedQuantity().getValue());

            var row = jdbcTemplate.queryForMap("SELECT sq, lq FROM inventory WHERE id = 10001");
            assertEquals(20000, row.get("sq"));
            assertEquals(10000, row.get("lq"));
        }

        @Test
        @DisplayName("LOCK-FUNC-007: reserveRatio=0-锁定全部额度")
        void testZeroReserveRatio() {
            insertInventory(10001L, 10000, 0, 0, 0);

            LockResult result = lockAppService.lockInventory(10001L, 10000, "lock-zero-001", 0.0);

            assertTrue(result.isSuccess());
            assertEquals(10000, result.getActualLockQuantity().getValue());

            var row = jdbcTemplate.queryForMap("SELECT sq, lq FROM inventory WHERE id = 10001");
            assertEquals(10000, row.get("lq"));
        }

        @Test
        @DisplayName("LOCK-FUNC-008: 锁库存释放-复用合并提交流程")
        void testReleaseLock() {
            insertInventory(10001L, 20000, 0, 0, 0);

            LockResult result = lockAppService.lockInventory(10001L, 1000, "lock-release-001", 0.1);
            assertTrue(result.isSuccess());

            lockAppService.releaseLock(result.getLockOrderId());

            var row = jdbcTemplate.queryForMap("SELECT lq FROM inventory WHERE id = 10001");
            assertEquals(0, row.get("lq"));

            var orderRow = jdbcTemplate.queryForMap(
                    "SELECT status FROM lock_inventory_order WHERE id = ?",
                    result.getLockOrderId());
            assertEquals("ARCHIVED", orderRow.get("status"));
        }

        @Test
        @DisplayName("LOCK-FUNC-010: reserve-ratio与min-lock-quantity死区验证")
        void testDeadZone() {
            insertInventory(10001L, 5001, 0, 0, 5000);

            org.junit.jupiter.api.Assertions.assertThrows(LockQuantityExceededException.class,
                    () -> lockAppService.lockInventory(10001L, 1000, "lock-deadzone-001", 0.1));
        }
    }
}
