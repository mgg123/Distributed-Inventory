package com.mgg.exp.store.domain.deduction.repository;

import com.mgg.exp.store.domain.deduction.entity.DeductionDetail;

import java.util.List;

public interface DeductionDetailRepository {

    DeductionDetail save(DeductionDetail detail);

    int markPendingAsMerged(String lockOrderId, String batchId);

    Integer calculateNetDeduction(String batchId);

    int cancelPending(String id);

    int cancelMerged(String id);

    int confirmOccupied(String id);

    int refundOccupied(String id);

    List<DeductionDetail> findByLockOrderIdAndStatus(String lockOrderId, String status);

    DeductionDetail findById(String id);

    DeductionDetail findByOrderIdAndSkuId(String orderId, Long skuId);
}
