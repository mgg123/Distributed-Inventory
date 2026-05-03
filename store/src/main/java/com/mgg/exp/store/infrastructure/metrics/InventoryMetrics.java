package com.mgg.exp.store.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class InventoryMetrics {

    private final Counter deductSuccessCounter;
    private final Counter deductFailedCounter;
    private final Counter deductDegradedCounter;
    private final Counter mergeCommitCounter;
    private final Counter lockInventoryCounter;
    private final Counter refundCounter;
    private final Counter emergencyDegradeCounter;
    private final Timer deductTimer;
    private final Timer mergeCommitTimer;
    private final Timer lockInventoryTimer;

    public InventoryMetrics(MeterRegistry registry) {
        this.deductSuccessCounter = Counter.builder("inventory.deduct.success")
                .description("Successful inventory deductions")
                .register(registry);

        this.deductFailedCounter = Counter.builder("inventory.deduct.failed")
                .description("Failed inventory deductions")
                .register(registry);

        this.deductDegradedCounter = Counter.builder("inventory.deduct.degraded")
                .description("DB degraded inventory deductions")
                .register(registry);

        this.mergeCommitCounter = Counter.builder("inventory.merge.commit")
                .description("Merge commit operations")
                .register(registry);

        this.lockInventoryCounter = Counter.builder("inventory.lock")
                .description("Lock inventory operations")
                .register(registry);

        this.refundCounter = Counter.builder("inventory.refund")
                .description("Refund operations")
                .register(registry);

        this.emergencyDegradeCounter = Counter.builder("inventory.emergency.degrade")
                .description("Emergency degrade triggers")
                .register(registry);

        this.deductTimer = Timer.builder("inventory.deduct.duration")
                .description("Inventory deduction duration")
                .register(registry);

        this.mergeCommitTimer = Timer.builder("inventory.merge.commit.duration")
                .description("Merge commit duration")
                .register(registry);

        this.lockInventoryTimer = Timer.builder("inventory.lock.duration")
                .description("Lock inventory duration")
                .register(registry);
    }

    public void recordDeductSuccess() {
        deductSuccessCounter.increment();
    }

    public void recordDeductFailed() {
        deductFailedCounter.increment();
    }

    public void recordDeductDegraded() {
        deductDegradedCounter.increment();
    }

    public void recordMergeCommit() {
        mergeCommitCounter.increment();
    }

    public void recordLockInventory() {
        lockInventoryCounter.increment();
    }

    public void recordRefund() {
        refundCounter.increment();
    }

    public void recordEmergencyDegrade() {
        emergencyDegradeCounter.increment();
    }

    public Timer getDeductTimer() {
        return deductTimer;
    }

    public Timer getMergeCommitTimer() {
        return mergeCommitTimer;
    }

    public Timer getLockInventoryTimer() {
        return lockInventoryTimer;
    }
}
