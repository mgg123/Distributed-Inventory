package com.mgg.exp.store.domain.inventory.repository;

import com.mgg.exp.store.domain.inventory.aggregate.InventoryAggregate;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;

import java.util.Optional;

public interface InventoryRepository {

    Optional<InventoryAggregate> findBySkuId(SkuId skuId);

    void save(InventoryAggregate aggregate);

    int lockInventory(SkuId skuId, Integer actualLockQuantity);

    int mergeCommit(SkuId skuId, Integer netDeduction, Integer currentLockQuantity);

    int directDeduct(SkuId skuId, Integer quantity);

    int confirmPayment(SkuId skuId, Integer quantity);

    int cancelMerged(SkuId skuId, Integer quantity);

    int refundOccupied(SkuId skuId, Integer quantity);

    int emergencyResetLq(SkuId skuId);
}
