package com.mgg.exp.store.domain.inventory.repository;

import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LockOrderRepository {

    LockInventoryOrder save(LockInventoryOrder lockOrder);

    Optional<LockInventoryOrder> findById(LockOrderId id);

    Optional<LockInventoryOrder> findByIdempotentKey(String idempotentKey);

    List<LockInventoryOrder> findExpiredActive(LocalDateTime now);

    int updateStatusToArchived(LockOrderId id);

    int updateMergeCompleted(LockOrderId id);

    int archiveAllBySkuId(Long skuId);

    long countActiveBySkuId(Long skuId);

    List<LockInventoryOrder> findActiveBySkuId(Long skuId);
}
