package com.mgg.exp.store.domain.inventory.service;

import com.mgg.exp.store.domain.inventory.aggregate.InventoryAggregate;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;

public class InventoryDomainService {

    public LockResult lock(InventoryAggregate aggregate, Quantity lockAmount,
                           double reserveRatio) {
        return aggregate.lock(lockAmount, reserveRatio);
    }

    public void mergeCommit(InventoryAggregate aggregate, Quantity netDeduction,
                            Quantity currentLockQuantity) {
        aggregate.mergeCommit(netDeduction, currentLockQuantity);
    }

    public void release(InventoryAggregate aggregate, Quantity releaseAmount) {
        aggregate.release(releaseAmount);
    }
}
