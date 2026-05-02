package com.mgg.exp.store.infrastructure.repository;

import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.domain.refund.entity.RefundDetail;
import com.mgg.exp.store.domain.refund.repository.RefundDetailRepository;
import com.mgg.exp.store.infrastructure.converter.RefundDetailConverter;
import com.mgg.exp.store.infrastructure.dataobject.RefundDetailPO;
import com.mgg.exp.store.infrastructure.mapper.RefundDetailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefundDetailRepositoryImpl implements RefundDetailRepository {

    private final RefundDetailMapper refundDetailMapper;
    private final RefundDetailConverter converter;

    @Override
    public RefundDetail save(RefundDetail detail) {
        RefundDetailPO po = converter.toPO(
                detail.getId(),
                detail.getSkuId().value(),
                detail.getRefundQuantity().getValue(),
                detail.getDeductPath().name(),
                detail.getOrderId(),
                detail.getRefDetailId(),
                detail.getRefundRequestId()
        );
        refundDetailMapper.insert(po);
        return detail;
    }

    @Override
    public Optional<RefundDetail> findByRefDetailAndRequestId(String refDetailId,
                                                               String refundRequestId) {
        RefundDetailPO po = refundDetailMapper.selectByRefDetailAndRequestId(
                refDetailId, refundRequestId);
        return Optional.ofNullable(po).map(this::toEntity);
    }

    private RefundDetail toEntity(RefundDetailPO po) {
        RefundDetail detail = new RefundDetail();
        detail.setId(po.getId());
        detail.setSkuId(new SkuId(po.getSkuId()));
        detail.setRefundQuantity(Quantity.of(po.getRefundQuantity()));
        detail.setDeductPath(DeductPath.valueOf(po.getDeductPath()));
        detail.setOrderId(po.getOrderId());
        detail.setRefDetailId(po.getRefDetailId());
        detail.setRefundRequestId(po.getRefundRequestId());
        return detail;
    }
}
