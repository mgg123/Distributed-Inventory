package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.query.DeductionDetailVO;
import com.mgg.exp.store.domain.deduction.entity.DeductionDetail;
import com.mgg.exp.store.domain.deduction.repository.DeductionDetailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeductionDetailQueryExe {

    private final DeductionDetailRepository deductionDetailRepository;

    public DeductionDetailVO queryByOrderId(String orderId) {
        DeductionDetail detail = deductionDetailRepository.findByOrderIdAndSkuId(orderId, null);
        return detail != null ? toVO(detail) : null;
    }

    public DeductionDetailVO queryById(String detailId) {
        DeductionDetail detail = deductionDetailRepository.findById(detailId);
        return detail != null ? toVO(detail) : null;
    }

    private DeductionDetailVO toVO(DeductionDetail detail) {
        DeductionDetailVO vo = new DeductionDetailVO();
        vo.setDetailId(detail.getId());
        vo.setOrderId(detail.getOrderId() != null ? detail.getOrderId().value() : null);
        vo.setSkuId(detail.getSkuId() != null ? detail.getSkuId().value() : null);
        vo.setQuantity(detail.getQuantity() != null ? detail.getQuantity().getValue() : null);
        vo.setDeductPath(detail.getDeductPath() != null ? detail.getDeductPath().name() : null);
        vo.setStatus(detail.getStatus() != null ? detail.getStatus().name() : null);
        vo.setLockOrderId(detail.getLockOrderId());
        vo.setBucketIndex(detail.getBucketIndex());
        vo.setMergeBatchId(detail.getMergeBatchId());
        return vo;
    }
}
