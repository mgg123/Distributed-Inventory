package com.mgg.exp.store.domain.refund.repository;

import com.mgg.exp.store.domain.refund.entity.RefundDetail;

import java.util.Optional;

public interface RefundDetailRepository {

    RefundDetail save(RefundDetail detail);

    Optional<RefundDetail> findByRefDetailAndRequestId(String refDetailId, String refundRequestId);
}
