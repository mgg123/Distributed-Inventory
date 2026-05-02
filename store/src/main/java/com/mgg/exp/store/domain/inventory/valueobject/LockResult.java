package com.mgg.exp.store.domain.inventory.valueobject;

public final class LockResult {

    private final boolean success;
    private final String lockOrderId;
    private final Quantity actualLockQuantity;
    private final Quantity reservedQuantity;
    private final String errorCode;

    private LockResult(boolean success, String lockOrderId, Quantity actualLockQuantity,
                       Quantity reservedQuantity, String errorCode) {
        this.success = success;
        this.lockOrderId = lockOrderId;
        this.actualLockQuantity = actualLockQuantity;
        this.reservedQuantity = reservedQuantity;
        this.errorCode = errorCode;
    }

    public static LockResult success(String lockOrderId, Quantity actualLockQuantity,
                                     Quantity reservedQuantity) {
        return new LockResult(true, lockOrderId, actualLockQuantity, reservedQuantity, null);
    }

    public static LockResult insufficient() {
        return new LockResult(false, null, null, null, "LOCK_QUANTITY_EXCEEDED");
    }

    public static LockResult alreadyArchived() {
        return new LockResult(false, null, null, null, "LOCK_ORDER_ALREADY_ARCHIVED");
    }

    public static LockResult idempotentHit(String lockOrderId) {
        return new LockResult(true, lockOrderId, null, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getLockOrderId() {
        return lockOrderId;
    }

    public Quantity getActualLockQuantity() {
        return actualLockQuantity;
    }

    public Quantity getReservedQuantity() {
        return reservedQuantity;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
