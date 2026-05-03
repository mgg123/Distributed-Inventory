package com.mgg.exp.store.infrastructure.repository;

import com.mgg.exp.store.domain.inventory.entity.LockInventoryOrder;
import com.mgg.exp.store.domain.inventory.repository.LockOrderRepository;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderId;
import com.mgg.exp.store.domain.inventory.valueobject.LockOrderStatus;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.infrastructure.converter.LockOrderConverter;
import com.mgg.exp.store.infrastructure.dataobject.LockInventoryOrderPO;
import com.mgg.exp.store.infrastructure.mapper.LockOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class LockOrderRepositoryImpl implements LockOrderRepository {

    private final LockOrderMapper lockOrderMapper;
    private final LockOrderConverter converter;

    @Override
    public LockInventoryOrder save(LockInventoryOrder lockOrder) {
        LockInventoryOrderPO po = converter.toPO(
                lockOrder.getId().value(),
                lockOrder.getSkuId().value(),
                lockOrder.getLockQuantity().getValue(),
                lockOrder.getBucketInfo(),
                lockOrder.getExpireTime(),
                lockOrder.getIdempotentKey()
        );
        lockOrderMapper.insert(po);
        return lockOrder;
    }

    @Override
    public Optional<LockInventoryOrder> findById(LockOrderId id) {
        LockInventoryOrderPO po = lockOrderMapper.selectById(id.value());
        return Optional.ofNullable(po).map(this::toEntity);
    }

    @Override
    public Optional<LockInventoryOrder> findByIdempotentKey(String idempotentKey) {
        LockInventoryOrderPO po = lockOrderMapper.selectByIdempotentKey(idempotentKey);
        return Optional.ofNullable(po).map(this::toEntity);
    }

    @Override
    public List<LockInventoryOrder> findExpiredActive(LocalDateTime now) {
        return lockOrderMapper.selectExpiredActive(now).stream()
                .map(this::toEntity).toList();
    }

    @Override
    public int updateStatusToArchived(LockOrderId id) {
        return lockOrderMapper.updateStatusToArchived(id.value());
    }

    @Override
    public int updateMergeCompleted(LockOrderId id) {
        return lockOrderMapper.updateMergeCompleted(id.value());
    }

    @Override
    public int archiveAllBySkuId(Long skuId) {
        return lockOrderMapper.archiveAllBySkuId(skuId);
    }

    @Override
    public long countActiveBySkuId(Long skuId) {
        return lockOrderMapper.selectCount(
                new LambdaQueryWrapper<LockInventoryOrderPO>()
                        .eq(LockInventoryOrderPO::getSkuId, skuId)
                        .eq(LockInventoryOrderPO::getStatus, "ACTIVE"));
    }

    @Override
    public List<LockInventoryOrder> findActiveBySkuId(Long skuId) {
        return lockOrderMapper.selectActiveBySkuId(skuId).stream()
                .map(this::toEntity).toList();
    }

    private LockInventoryOrder toEntity(LockInventoryOrderPO po) {
        return new LockInventoryOrder(
                new LockOrderId(po.getId()),
                new SkuId(po.getSkuId()),
                Quantity.of(po.getLockQuantity()),
                po.getBucketInfo(),
                po.getExpireTime(),
                po.getIdempotentKey()
        );
    }
}
