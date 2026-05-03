package com.mgg.exp.store.adapter.web;

import com.mgg.exp.store.adapter.dto.CommonResponse;
import com.mgg.exp.store.adapter.dto.command.CancelInventoryCmd;
import com.mgg.exp.store.adapter.dto.command.ConfirmPaymentCmd;
import com.mgg.exp.store.adapter.dto.command.DeductInventoryCmd;
import com.mgg.exp.store.adapter.dto.command.EmergencyUnlockCmd;
import com.mgg.exp.store.adapter.dto.command.LockInventoryCmd;
import com.mgg.exp.store.adapter.dto.command.MergeCommitCmd;
import com.mgg.exp.store.adapter.dto.command.RefundInventoryCmd;
import com.mgg.exp.store.adapter.exe.EmergencyUnlockCmdExe;
import com.mgg.exp.store.adapter.exe.InventoryDeductCmdExe;
import com.mgg.exp.store.adapter.exe.InventoryLockCmdExe;
import com.mgg.exp.store.adapter.exe.InventoryMergeCmdExe;
import com.mgg.exp.store.adapter.exe.InventoryRefundCmdExe;
import com.mgg.exp.store.common.enums.ErrorCodeEnum;
import com.mgg.exp.store.common.exception.InventoryException;
import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import com.mgg.exp.store.domain.deduction.valueobject.MergeResult;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.refund.valueobject.RefundResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Tag(name = "库存写操作", description = "库存锁库存/扣减/取消/退款/确认/合并/紧急解锁接口")
public class InventoryWriteController {

    private final InventoryLockCmdExe lockCmdExe;
    private final InventoryDeductCmdExe deductCmdExe;
    private final InventoryRefundCmdExe refundCmdExe;
    private final InventoryMergeCmdExe mergeCmdExe;
    private final EmergencyUnlockCmdExe emergencyUnlockCmdExe;

    @PostMapping("/lock")
    @Operation(summary = "锁库存", description = "将DB行库存从sq预锁定到lq，初始化Redis分桶")
    public CommonResponse<Map<String, Object>> lockInventory(
            @Valid @RequestBody LockInventoryCmd cmd) {
        LockResult result = lockCmdExe.execute(cmd);
        if (!result.isSuccess()) {
            ErrorCodeEnum errorCode = ErrorCodeEnum.LOCK_QUANTITY_EXCEEDED;
            if ("LOCK_ORDER_ALREADY_ARCHIVED".equals(result.getErrorCode())) {
                errorCode = ErrorCodeEnum.LOCK_ORDER_ALREADY_ARCHIVED;
            }
            return CommonResponse.failed(errorCode.getCode(), errorCode.getMessage());
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("lockOrderId", result.getLockOrderId());
        if (result.getActualLockQuantity() != null) {
            data.put("actualLockQuantity", result.getActualLockQuantity().getValue());
        }
        if (result.getReservedQuantity() != null) {
            data.put("reservedQuantity", result.getReservedQuantity().getValue());
        }
        return CommonResponse.success(data);
    }

    @PostMapping("/lock/{lockOrderId}/release")
    @Operation(summary = "释放锁库存", description = "主动释放锁库存，触发合并提交流程")
    public CommonResponse<Void> releaseLock(@PathVariable String lockOrderId) {
        lockCmdExe.releaseLock(lockOrderId);
        return CommonResponse.success();
    }

    @PostMapping("/deduct")
    @Operation(summary = "扣减库存", description = "扣减库存，优先Redis分桶，降级走DB")
    public CommonResponse<Map<String, Object>> deductInventory(
            @Valid @RequestBody DeductInventoryCmd cmd) {
        DeductResult result = deductCmdExe.execute(cmd);
        if (!result.isSuccess()) {
            return CommonResponse.failed(ErrorCodeEnum.INSUFFICIENT_STOCK.getCode(),
                    ErrorCodeEnum.INSUFFICIENT_STOCK.getMessage());
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("detailId", result.getDetailId());
        data.put("deductPath", result.isBucketExhausted() ? "MERGE_BUCKETS" : "MERGE_BUCKETS");
        if (result.getLuaResult() == -1) {
            data.put("deductPath", "DIRECT_DB");
        }
        return CommonResponse.success(data);
    }

    @PostMapping("/cancel")
    @Operation(summary = "取消订单", description = "取消订单，根据明细状态执行对应回补操作")
    public CommonResponse<Map<String, Object>> cancelInventory(
            @Valid @RequestBody CancelInventoryCmd cmd) {
        RefundResult result = refundCmdExe.cancel(cmd);
        if (!result.isSuccess()) {
            return CommonResponse.failed(ErrorCodeEnum.INTERNAL_ERROR.getCode(),
                    result.getErrorCode());
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("refundDetailId", result.getRefundId());
        return CommonResponse.success(data);
    }

    @PostMapping("/refund")
    @Operation(summary = "退款", description = "退款，将明细从OCCUPIED更新为REFUNDED，oq回补sq")
    public CommonResponse<Map<String, Object>> refundInventory(
            @Valid @RequestBody RefundInventoryCmd cmd) {
        RefundResult result = refundCmdExe.refund(cmd);
        if (!result.isSuccess()) {
            return CommonResponse.failed(ErrorCodeEnum.INTERNAL_ERROR.getCode(),
                    result.getErrorCode());
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("refundDetailId", result.getRefundId());
        return CommonResponse.success(data);
    }

    @PostMapping("/confirm")
    @Operation(summary = "付款确认", description = "确认付款，将明细从MERGED更新为OCCUPIED，wq转移到oq")
    public CommonResponse<Map<String, Object>> confirmPayment(
            @Valid @RequestBody ConfirmPaymentCmd cmd) {
        RefundResult result = refundCmdExe.confirmPayment(cmd);
        if (!result.isSuccess()) {
            return CommonResponse.failed(ErrorCodeEnum.INTERNAL_ERROR.getCode(),
                    result.getErrorCode());
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("detailId", result.getRefundId());
        data.put("currentStatus", "OCCUPIED");
        return CommonResponse.success(data);
    }

    @PostMapping("/merge")
    @Operation(summary = "手动触发合并提交", description = "运维接口，手动触发指定lockOrder的合并提交")
    public CommonResponse<MergeResult> mergeCommit(@Valid @RequestBody MergeCommitCmd cmd) {
        MergeResult result = mergeCmdExe.execute(cmd);
        if (!result.isSuccess()) {
            return CommonResponse.failed(ErrorCodeEnum.MERGE_IN_PROGRESS.getCode(),
                    result.getErrorCode());
        }
        return CommonResponse.success(result);
    }

    @PostMapping("/emergency-unlock")
    @Operation(summary = "紧急解锁", description = "紧急解锁，当Redis不可用时释放lq使DB降级路径可用")
    public CommonResponse<Void> emergencyUnlock(@Valid @RequestBody EmergencyUnlockCmd cmd) {
        emergencyUnlockCmdExe.execute(cmd);
        return CommonResponse.success();
    }
}
