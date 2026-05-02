package com.mgg.exp.store.domain.inventory.aggregate;

import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;

public class InventoryAggregate {

    private SkuId skuId;
    private Quantity sq;
    private Quantity wq;
    private Quantity oq;
    private Quantity lq;

    public InventoryAggregate(SkuId skuId, Quantity sq, Quantity wq, Quantity oq, Quantity lq) {
        this.skuId = skuId;
        this.sq = sq;
        this.wq = wq;
        this.oq = oq;
        this.lq = lq;
    }

    public LockResult lock(Quantity lockAmount, double reserveRatio) {
        Quantity available = sq.subtract(lq);
        if (available.isLessThanOrEqual(Quantity.zero())) {
            return LockResult.insufficient();
        }
        Quantity actualLock = computeActualLockQuantity(lockAmount, available, reserveRatio);
        if (actualLock.isZero()) {
            return LockResult.insufficient();
        }
        this.lq = lq.add(actualLock);
        Quantity reserved = Quantity.of((int) (available.getValue() * reserveRatio));
        return LockResult.success(null, actualLock, reserved);
    }

    public void mergeCommit(Quantity netDeduction, Quantity currentLockQuantity) {
        this.sq = sq.subtract(netDeduction);
        this.wq = wq.add(netDeduction);
        this.lq = lq.subtract(currentLockQuantity);
    }

    public void release(Quantity releaseAmount) {
        this.lq = lq.subtract(releaseAmount);
    }

    public Quantity getAvailableQuantity() {
        return sq.subtract(lq);
    }

    private Quantity computeActualLockQuantity(Quantity lockAmount, Quantity available,
                                                double reserveRatio) {
        int maxLock = (int) (available.getValue() * (1 - reserveRatio));
        int actual = Math.min(lockAmount.getValue(), maxLock);
        return Quantity.of(Math.max(actual, 0));
    }

    public SkuId getSkuId() {
        return skuId;
    }

    public Quantity getSq() {
        return sq;
    }

    public Quantity getWq() {
        return wq;
    }

    public Quantity getOq() {
        return oq;
    }

    public Quantity getLq() {
        return lq;
    }
}
