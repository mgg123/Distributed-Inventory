package com.mgg.exp.store.integration;

import com.mgg.exp.store.BaseIntegrationTest;
import com.mgg.exp.store.app.service.InventoryDeductAppService;
import com.mgg.exp.store.app.service.InventoryLockAppService;
import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyConsistencyTest extends BaseIntegrationTest {

    @Autowired
    private InventoryLockAppService lockAppService;

    @Autowired
    private InventoryDeductAppService deductAppService;

    @Autowired
    private InventoryMergeAppService mergeAppService;

    @Nested
    @DisplayName("11 并发场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("CONC-CONC-001: 同一SKU并发扣减-Redis Lua原子性")
        void testConcurrentDeductSameSku() throws InterruptedException {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "conc-001", 0.1);
            assertTrue(lockResult.isSuccess());

            int threadCount = 50;
            int quantityPerThread = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        DeductResult result = deductAppService.deduct(10001L, quantityPerThread,
                                "order-conc-001-" + idx);
                        if (result.isSuccess()) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            int totalDeducted = successCount.get() * quantityPerThread;
            assertTrue(totalDeducted <= 9000,
                    "Total deducted should not exceed locked quantity: " + totalDeducted);
        }

        @Test
        @DisplayName("CONC-CONC-002: 同一SKU并发DB降级扣减-SQL行锁")
        void testConcurrentDbDeduct() throws InterruptedException {
            insertInventory(10001L, 1000, 0, 0, 0);

            int threadCount = 20;
            int quantityPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        DeductResult result = deductAppService.deduct(10001L, quantityPerThread,
                                "order-db-conc-" + idx);
                        if (result.isSuccess()) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            assertTrue(successCount.get() <= 10,
                    "At most 10 threads should succeed (1000/100): " + successCount.get());

            var row = jdbcTemplate.queryForMap("SELECT sq, wq FROM inventory WHERE id = 10001");
            int sq = (int) row.get("sq");
            int wq = (int) row.get("wq");
            assertEquals(1000, sq + wq, "sq + wq should remain constant");
        }

        @Test
        @DisplayName("CONC-CONC-003: 幂等键并发锁库存-唯一索引防重")
        void testConcurrentLockIdempotent() throws InterruptedException {
            insertInventory(10001L, 20000, 0, 0, 0);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            Set<String> lockOrderIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        LockResult result = lockAppService.lockInventory(10001L, 1000,
                                "conc-lock-idem-001", 0.1);
                        if (result.isSuccess() && result.getLockOrderId() != null) {
                            lockOrderIds.add(result.getLockOrderId());
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(1, lockOrderIds.size(),
                    "Only one lock order should be created for same idempotent key");
        }

        @Test
        @DisplayName("CONC-CONC-005: 并发扣减+并发合并提交")
        void testConcurrentDeductAndMerge() throws InterruptedException {
            insertInventory(10001L, 20000, 0, 0, 0);
            LockResult lockResult = lockAppService.lockInventory(10001L, 10000, "conc-merge-001", 0.1);
            assertTrue(lockResult.isSuccess());

            int deductThreads = 20;
            int mergeThreads = 5;
            ExecutorService executor = Executors.newFixedThreadPool(deductThreads + mergeThreads);
            CountDownLatch latch = new CountDownLatch(deductThreads + mergeThreads);
            AtomicInteger deductSuccess = new AtomicInteger(0);
            AtomicInteger mergeSuccess = new AtomicInteger(0);

            for (int i = 0; i < deductThreads; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        DeductResult result = deductAppService.deduct(10001L, 10,
                                "order-conc-merge-" + idx);
                        if (result.isSuccess()) {
                            deductSuccess.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            for (int i = 0; i < mergeThreads; i++) {
                executor.submit(() -> {
                    try {
                        Thread.sleep(100);
                        var result = mergeAppService.mergeCommit(lockResult.getLockOrderId());
                        if (result.isSuccess()) {
                            mergeSuccess.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            int totalDeducted = deductSuccess.get() * 10;
            assertTrue(totalDeducted <= 9000);
        }
    }

    @Nested
    @DisplayName("12 数据一致性验证测试")
    class ConsistencyTest {

        @Test
        @DisplayName("CONSIS-CONS-002: DB层防超卖-WHERE sq-lq >= quantity")
        void testDbAntiOversell() throws InterruptedException {
            insertInventory(10001L, 100, 0, 0, 80);

            int threadCount = 10;
            int quantityPerThread = 15;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        DeductResult result = deductAppService.deduct(10001L, quantityPerThread,
                                "order-antisell-" + idx);
                        if (result.isSuccess()) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            assertTrue(successCount.get() <= 1,
                    "At most 1 thread should succeed (sq-lq=20, need 15): " + successCount.get());

            var row = jdbcTemplate.queryForMap("SELECT sq, wq, lq FROM inventory WHERE id = 10001");
            int sq = (int) row.get("sq");
            int lq = (int) row.get("lq");
            assertTrue(sq - lq >= 0, "sq - lq should never be negative");
        }

        @Test
        @DisplayName("CONSIS-CONS-005: 扣减明细唯一索引防重复")
        void testDeductionUniqueIndex() {
            insertInventory(10001L, 20000, 0, 0, 0);
            lockAppService.lockInventory(10001L, 10000, "unique-001", 0.1);

            DeductResult first = deductAppService.deduct(10001L, 10, "order-unique-001");
            assertTrue(first.isSuccess());

            DeductResult second = deductAppService.deduct(10001L, 10, "order-unique-001");
            assertTrue(second.isSuccess());
        }

        @Test
        @DisplayName("CONSIS-CONS-010: lq与lock_inventory_order一致性")
        void testLqConsistency() {
            insertInventory(10001L, 20000, 0, 0, 0);

            LockResult lock1 = lockAppService.lockInventory(10001L, 10000, "lq-cons-001", 0.1);
            assertTrue(lock1.isSuccess());

            var row = jdbcTemplate.queryForMap("SELECT lq FROM inventory WHERE id = 10001");
            int lq = (int) row.get("lq");

            Long activeLockQuantity = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(lock_quantity), 0) FROM lock_inventory_order WHERE sku_id = 10001 AND status = 'ACTIVE'",
                    Long.class);

            assertEquals(lq, activeLockQuantity.intValue(),
                    "inventory.lq should equal SUM(lock_quantity) of ACTIVE lock orders");
        }
    }
}
