package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.command.DeductInventoryCmd;
import com.mgg.exp.store.app.service.InventoryDeductAppService;
import com.mgg.exp.store.domain.deduction.valueobject.DeductResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryDeductCmdExe {

    private final InventoryDeductAppService deductAppService;

    public DeductResult execute(DeductInventoryCmd cmd) {
        return deductAppService.deduct(
                cmd.getSkuId(),
                cmd.getQuantity(),
                cmd.getOrderId()
        );
    }
}
