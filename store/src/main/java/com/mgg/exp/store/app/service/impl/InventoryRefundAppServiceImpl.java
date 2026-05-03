package com.mgg.exp.store.app.service.impl;

import com.mgg.exp.store.app.service.InventoryRefundAppService;
import com.mgg.exp.store.common.enums.ErrorCodeEnum;
import com.mgg.exp.store.common.exception.InventoryException;
import com.mgg.exp.store.common.util.IdGenerator;
import com.mgg.exp.store.domain.deduction.entity.DeductionDetail;
import com.mgg.exp.store.domain.deduction.repository.DeductionDetailRepository;
import com.mgg.exp.store.domain.deduction.valueobject.DeductPath;
import com.mgg.exp.store.domain.deduction.valueobject.DeductionStatus;
import com.mgg.exp.store.domain.gateway.RedisBucketGateway;
import com.mgg.exp.store.domain.inventory.repository.InventoryRepository;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import com.mgg.exp.store.domain.inventory.valueobject.SkuId;
import com.mgg.exp.store.domain.refund.entity.RefundDetail;
import com.mgg.exp.store.domain.refund.repository.RefundDetailRepository;
import com.mgg.exp.store.domain.refund.service.RefundDomainService;
import com.mgg.exp.store.domain.refund.valueobject.RefundQuantity;
import com.mgg.exp.store.domain.refund.valueobject.RefundResult;
import com.mgg.exp.store.infrastructure.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryRefundAppServiceImpl implements InventoryRefundAppService {

    private final InventoryRepository inventoryRepository;
    private final DeductionDetailRepository deductionDetailRepository;
    private final RefundDetailRepository refundDetailRepository;
    private final RedisBucketGateway redisBucketGateway;
    private final RefundDomainService refundDomainService;
    private final StoreProperties storeProperties;

    @Override
    @Transactional
    public RefundResult cancel(String detailId) {
        DeductionDetail detail = deductionDetailRepository.findById(detailId);
        if (detail == null) {
            throw new InventoryException(ErrorCodeEnum.STOCK_NOT_FOUND,
                    "deduction detail not found: " + detailId);
        }

        if (detail.isPending()) {
            return cancelPending(detail);
        }

        if (detail.isMerged()) {
            return cancelMerged(detail);
        }

        if (detail.isOccupied()) {
            return RefundResult.failed("OCCUPIED_CANNOT_CANCEL");
        }

        return RefundResult.failed("INVALID_STATUS_FOR_CANCEL");
    }

    @Override
    @Transactional
    public RefundResult refund(String detailId, Integer quantity, String refundRequestId) {
        if (refundRequestId != null) {
            Optional<RefundDetail> existing = refundDetailRepository.findByRefDetailAndRequestId(
                    detailId, refundRequestId);
            if (existing.isPresent()) {
                log.info("refund idempotent hit, detailId: {}, refundRequestId: {}",
                        detailId, refundRequestId);
                return RefundResult.skipped("IDEMPOTENT_HIT");
            }
        }

        DeductionDetail detail = deductionDetailRepository.findById(detailId);
        if (detail == null) {
            throw new InventoryException(ErrorCodeEnum.STOCK_NOT_FOUND,
                    "deduction detail not found: " + detailId);
        }

        if (!detail.isOccupied()) {
            return RefundResult.failed("NOT_OCCUPIED");
        }

        int refundQty = quantity != null ? quantity : detail.getQuantity().getValue();
        if (refundQty <= 0 || refundQty > detail.getQuantity().getValue()) {
            return RefundResult.failed("INVALID_REFUND_QUANTITY");
        }

        String refundId = IdGenerator.nextIdStr();
        try {
            RefundDetail refundDetail = refundDomainService.createRefundDetail(
                    refundId,
                    detail.getSkuId(),
                    Quantity.of(refundQty),
                    detail.getDeductPath(),
                    detail.getOrderId().value(),
                    detailId,
                    refundRequestId);
            refundDetailRepository.save(refundDetail);
        } catch (DuplicateKeyException e) {
            log.warn("refund idempotent hit, detailId: {}, refundRequestId: {}",
                    detailId, refundRequestId);
            return RefundResult.skipped("IDEMPOTENT_HIT");
        }

        int updated = deductionDetailRepository.refundOccupied(detailId);
        if (updated == 0) {
            throw new InventoryException(ErrorCodeEnum.INTERNAL_ERROR,
                    "refund status transition failed, detailId: " + detailId);
        }

        int inventoryUpdated = inventoryRepository.refundOccupied(
                detail.getSkuId(), refundQty);
        if (inventoryUpdated == 0) {
            throw new InventoryException(ErrorCodeEnum.INTERNAL_ERROR,
                    "refund inventory update failed, oq insufficient");
        }

        log.info("refund success, detailId: {}, refundQty: {}, refundId: {}",
                detailId, refundQty, refundId);
        return RefundResult.success(refundId);
    }

    @Override
    @Transactional
    public RefundResult confirmPayment(String detailId) {
        DeductionDetail detail = deductionDetailRepository.findById(detailId);
        if (detail == null) {
            throw new InventoryException(ErrorCodeEnum.STOCK_NOT_FOUND,
                    "deduction detail not found: " + detailId);
        }

        if (!detail.isMerged()) {
            return RefundResult.failed("NOT_MERGED");
        }

        int updated = deductionDetailRepository.confirmOccupied(detailId);
        if (updated == 0) {
            throw new InventoryException(ErrorCodeEnum.INTERNAL_ERROR,
                    "confirm payment status transition failed, detailId: " + detailId);
        }

        int quantity = detail.getQuantity().getValue();
        int inventoryUpdated = inventoryRepository.confirmPayment(
                detail.getSkuId(), quantity);
        if (inventoryUpdated == 0) {
            throw new InventoryException(ErrorCodeEnum.INTERNAL_ERROR,
                    "confirm payment inventory update failed, wq insufficient");
        }

        log.info("confirmPayment success, detailId: {}, skuId: {}, quantity: {}",
                detailId, detail.getSkuId().value(), quantity);
        return RefundResult.success(detailId);
    }

    private RefundResult cancelPending(DeductionDetail detail) {
        if (detail.getDeductPath() == DeductPath.MERGE_BUCKETS
                && detail.getLockOrderId() != null
                && detail.getBucketIndex() != null) {
            boolean metaValid = redisBucketGateway.isBucketMetaValid(detail.getLockOrderId());
            if (metaValid) {
                int result = redisBucketGateway.incrRefund(
                        detail.getLockOrderId(),
                        detail.getBucketIndex(),
                        detail.getQuantity().getValue());
                if (result == 1) {
                    log.info("PENDING cancel: Redis INCR refund success, detailId: {}, " +
                            "lockOrderId: {}, bucketIndex: {}", detail.getId(),
                            detail.getLockOrderId(), detail.getBucketIndex());
                } else {
                    log.info("PENDING cancel: Redis INCR refund skipped (meta invalidated), " +
                            "detailId: {}", detail.getId());
                }
            } else {
                log.info("PENDING cancel: meta already invalidated, skip INCR refund, " +
                        "detailId: {}", detail.getId());
            }
        }

        int updated = deductionDetailRepository.cancelPending(detail.getId());
        if (updated == 0) {
            log.info("PENDING cancel: already cancelled by concurrent operation, detailId: {}",
                    detail.getId());
            return RefundResult.skipped("ALREADY_CANCELLED");
        }

        log.info("cancel PENDING success, detailId: {}", detail.getId());
        return RefundResult.success(null);
    }

    private RefundResult cancelMerged(DeductionDetail detail) {
        String refundId = IdGenerator.nextIdStr();
        try {
            RefundDetail refundDetail = refundDomainService.createRefundDetail(
                    refundId,
                    detail.getSkuId(),
                    detail.getQuantity(),
                    detail.getDeductPath(),
                    detail.getOrderId().value(),
                    detail.getId(),
                    null);
            refundDetailRepository.save(refundDetail);
        } catch (DuplicateKeyException e) {
            log.warn("MERGED cancel refund idempotent hit, detailId: {}", detail.getId());
            return RefundResult.skipped("IDEMPOTENT_HIT");
        }

        int updated = deductionDetailRepository.cancelMerged(detail.getId());
        if (updated == 0) {
            throw new InventoryException(ErrorCodeEnum.INTERNAL_ERROR,
                    "cancel merged status transition failed, detailId: " + detail.getId());
        }

        int quantity = detail.getQuantity().getValue();
        int inventoryUpdated = inventoryRepository.cancelMerged(
                detail.getSkuId(), quantity);
        if (inventoryUpdated == 0) {
            throw new InventoryException(ErrorCodeEnum.INTERNAL_ERROR,
                    "cancel merged inventory update failed, wq insufficient");
        }

        log.info("cancel MERGED success, detailId: {}, skuId: {}, quantity: {}",
                detail.getId(), detail.getSkuId().value(), quantity);
        return RefundResult.success(refundId);
    }
}
