package com.mgg.exp.store.infrastructure.repository;

import com.mgg.exp.store.domain.deduction.entity.DeductionDetail;
import com.mgg.exp.store.domain.deduction.repository.DeductionDetailRepository;
import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.deduction.valueobject.DeductionStatus;
import com.mgg.exp.store.domain.inventory.valueobject.OrderId;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.infrastructure.converter.DeductionDetailConverter;
import com.mgg.exp.store.infrastructure.dataobject.DeductionDetailPO;
import com.mgg.exp.store.infrastructure.mapper.DeductionDetailMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class DeductionDetailRepositoryImpl implements DeductionDetailRepository {

    private final DeductionDetailMapper deductionDetailMapper;
    private final DeductionDetailConverter converter;

    @Override
    public DeductionDetail save(DeductionDetail detail) {
        DeductionDetailPO po = converter.toPO(
                detail.getId(),
                detail.getSkuId().value(),
                detail.getQuantity().getValue(),
                detail.getDeductPath().name(),
                detail.getBucketIndex(),
                detail.getStatus().name(),
                detail.getOrderId().value(),
                detail.getLockOrderId()
        );
        deductionDetailMapper.insert(po);
        return detail;
    }

    @Override
    public int markPendingAsMerged(String lockOrderId, String batchId) {
        return deductionDetailMapper.markPendingAsMerged(lockOrderId, batchId);
    }

    @Override
    public Integer calculateNetDeduction(String batchId) {
        return deductionDetailMapper.calculateNetDeduction(batchId);
    }

    @Override
    public int cancelPending(String id) {
        return deductionDetailMapper.cancelPending(id);
    }

    @Override
    public int cancelMerged(String id) {
        return deductionDetailMapper.cancelMerged(id);
    }

    @Override
    public int confirmOccupied(String id) {
        return deductionDetailMapper.confirmOccupied(id);
    }

    @Override
    public int refundOccupied(String id) {
        return deductionDetailMapper.refundOccupied(id);
    }

    @Override
    public List<DeductionDetail> findByLockOrderIdAndStatus(String lockOrderId, String status) {
        List<DeductionDetailPO> pos = deductionDetailMapper.selectList(
                new LambdaQueryWrapper<DeductionDetailPO>()
                        .eq(DeductionDetailPO::getLockOrderId, lockOrderId)
                        .eq(DeductionDetailPO::getStatus, status));
        return pos.stream().map(this::toEntity).toList();
    }

    @Override
    public DeductionDetail findById(String id) {
        DeductionDetailPO po = deductionDetailMapper.selectById(id);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public DeductionDetail findByOrderIdAndSkuId(String orderId, Long skuId) {
        DeductionDetailPO po = deductionDetailMapper.selectByOrderIdAndSkuId(orderId, skuId);
        return po != null ? toEntity(po) : null;
    }

    private DeductionDetail toEntity(DeductionDetailPO po) {
        DeductionDetail detail = new DeductionDetail();
        detail.setId(po.getId());
        detail.setSkuId(new SkuId(po.getSkuId()));
        detail.setQuantity(Quantity.of(po.getQuantity()));
        detail.setDeductPath(DeductPath.valueOf(po.getDeductPath()));
        detail.setBucketIndex(po.getBucketIndex());
        detail.setStatus(DeductionStatus.valueOf(po.getStatus()));
        detail.setOrderId(new OrderId(po.getOrderId()));
        detail.setLockOrderId(po.getLockOrderId());
        detail.setMergeBatchId(po.getMergeBatchId());
        return detail;
    }
}
