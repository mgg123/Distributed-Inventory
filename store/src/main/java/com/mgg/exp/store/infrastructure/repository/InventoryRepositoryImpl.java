package com.mgg.exp.store.infrastructure.repository;

import com.mgg.exp.store.domain.inventory.aggregate.InventoryAggregate;
import com.mgg.exp.store.domain.inventory.repository.InventoryRepository;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.infrastructure.dataobject.InventoryPO;
import com.mgg.exp.store.infrastructure.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class InventoryRepositoryImpl implements InventoryRepository {

    private final InventoryMapper inventoryMapper;

    @Override
    public Optional<InventoryAggregate> findBySkuId(SkuId skuId) {
        InventoryPO po = inventoryMapper.selectById(skuId.value());
        if (po == null) {
            return Optional.empty();
        }
        return Optional.of(new InventoryAggregate(
                skuId,
                Quantity.of(po.getSq()),
                Quantity.of(po.getWq()),
                Quantity.of(po.getOq()),
                Quantity.of(po.getLq())
        ));
    }

    @Override
    public void save(InventoryAggregate aggregate) {
        InventoryPO po = new InventoryPO();
        po.setId(aggregate.getSkuId().value());
        po.setSq(aggregate.getSq().getValue());
        po.setWq(aggregate.getWq().getValue());
        po.setOq(aggregate.getOq().getValue());
        po.setLq(aggregate.getLq().getValue());
        inventoryMapper.insertOrUpdate(po);
    }

    @Override
    public int lockInventory(SkuId skuId, Integer actualLockQuantity) {
        return inventoryMapper.lockInventory(skuId.value(), actualLockQuantity);
    }

    @Override
    public int mergeCommit(SkuId skuId, Integer netDeduction, Integer currentLockQuantity) {
        return inventoryMapper.mergeCommit(skuId.value(), netDeduction, currentLockQuantity);
    }

    @Override
    public int directDeduct(SkuId skuId, Integer quantity) {
        return inventoryMapper.directDeduct(skuId.value(), quantity);
    }

    @Override
    public int confirmPayment(SkuId skuId, Integer quantity) {
        return inventoryMapper.confirmPayment(skuId.value(), quantity);
    }

    @Override
    public int cancelMerged(SkuId skuId, Integer quantity) {
        return inventoryMapper.cancelMerged(skuId.value(), quantity);
    }

    @Override
    public int refundOccupied(SkuId skuId, Integer quantity) {
        return inventoryMapper.refundOccupied(skuId.value(), quantity);
    }

    @Override
    public int emergencyResetLq(SkuId skuId) {
        return inventoryMapper.emergencyResetLq(skuId.value());
    }
}
