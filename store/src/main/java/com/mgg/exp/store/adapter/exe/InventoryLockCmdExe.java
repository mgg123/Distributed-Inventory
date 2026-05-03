package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.command.LockInventoryCmd;
import com.mgg.exp.store.app.service.InventoryLockAppService;
import com.mgg.exp.store.domain.inventory.valueobject.LockResult;
import com.mgg.exp.store.domain.inventory.valueobject.Quantity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryLockCmdExe {

    private final InventoryLockAppService lockAppService;

    public LockResult execute(LockInventoryCmd cmd) {
        return lockAppService.lockInventory(
                cmd.getSkuId(),
                cmd.getLockQuantity(),
                cmd.getIdempotentKey(),
                cmd.getReserveRatio()
        );
    }

    public void releaseLock(String lockOrderId) {
        lockAppService.releaseLock(lockOrderId);
    }
}
