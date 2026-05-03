package com.mgg.exp.store.adapter.web;

import com.mgg.exp.store.adapter.dto.CommonResponse;
import com.mgg.exp.store.adapter.dto.query.DeductionDetailVO;
import com.mgg.exp.store.adapter.dto.query.InventoryVO;
import com.mgg.exp.store.adapter.dto.query.LockOrderVO;
import com.mgg.exp.store.adapter.exe.DeductionDetailQueryExe;
import com.mgg.exp.store.adapter.exe.InventoryQueryExe;
import com.mgg.exp.store.adapter.exe.LockOrderQueryExe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Tag(name = "库存读操作", description = "库存查询/明细查询/锁库存单据查询接口")
public class InventoryReadController {

    private final InventoryQueryExe inventoryQueryExe;
    private final DeductionDetailQueryExe deductionDetailQueryExe;
    private final LockOrderQueryExe lockOrderQueryExe;

    @GetMapping("/{skuId}")
    @Operation(summary = "查询库存", description = "查询SKU维度的库存信息")
    public CommonResponse<InventoryVO> queryInventory(@PathVariable Long skuId) {
        InventoryVO vo = inventoryQueryExe.queryInventory(skuId);
        if (vo == null) {
            return CommonResponse.failed(101002, "库存记录不存在");
        }
        return CommonResponse.success(vo);
    }

    @GetMapping("/deduction/{orderId}")
    @Operation(summary = "查询扣减明细", description = "查询指定订单的扣减明细")
    public CommonResponse<DeductionDetailVO> queryDeductionDetail(
            @PathVariable String orderId) {
        DeductionDetailVO vo = deductionDetailQueryExe.queryByOrderId(orderId);
        if (vo == null) {
            return CommonResponse.failed(101002, "扣减明细不存在");
        }
        return CommonResponse.success(vo);
    }

    @GetMapping("/lock-order/{lockOrderId}")
    @Operation(summary = "查询锁库存单据", description = "查询指定锁库存单据详情")
    public CommonResponse<LockOrderVO> queryLockOrder(@PathVariable String lockOrderId) {
        LockOrderVO vo = lockOrderQueryExe.queryByLockOrderId(lockOrderId);
        if (vo == null) {
            return CommonResponse.failed(102002, "锁库存单据不存在");
        }
        return CommonResponse.success(vo);
    }
}
