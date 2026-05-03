package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.command.CancelInventoryCmd;
import com.mgg.exp.store.adapter.dto.command.ConfirmPaymentCmd;
import com.mgg.exp.store.adapter.dto.command.RefundInventoryCmd;
import com.mgg.exp.store.app.service.InventoryRefundAppService;
import com.mgg.exp.store.domain.deduction.entity.DeductionDetail;
import com.mgg.exp.store.domain.deduction.repository.DeductionDetailRepository;
import com.mgg.exp.store.domain.refund.valueobject.RefundResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryRefundCmdExe {

    private final InventoryRefundAppService refundAppService;
    private final DeductionDetailRepository deductionDetailRepository;

    public RefundResult cancel(CancelInventoryCmd cmd) {
        DeductionDetail detail = findDetailByOrderAndSku(cmd.getOrderId(), cmd.getSkuId());
        if (detail == null) {
            return RefundResult.failed("DETAIL_NOT_FOUND");
        }
        return refundAppService.cancel(detail.getId());
    }

    public RefundResult refund(RefundInventoryCmd cmd) {
        DeductionDetail detail = findDetailByOrderAndSku(cmd.getOrderId(), cmd.getSkuId());
        if (detail == null) {
            return RefundResult.failed("DETAIL_NOT_FOUND");
        }
        return refundAppService.refund(detail.getId(), cmd.getRefundQuantity(),
                cmd.getRefundRequestId());
    }

    public RefundResult confirmPayment(ConfirmPaymentCmd cmd) {
        DeductionDetail detail = findDetailByOrderAndSku(cmd.getOrderId(), cmd.getSkuId());
        if (detail == null) {
            return RefundResult.failed("DETAIL_NOT_FOUND");
        }
        return refundAppService.confirmPayment(detail.getId());
    }

    private DeductionDetail findDetailByOrderAndSku(String orderId, Long skuId) {
        return deductionDetailRepository.findByOrderIdAndSkuId(orderId, skuId);
    }
}
