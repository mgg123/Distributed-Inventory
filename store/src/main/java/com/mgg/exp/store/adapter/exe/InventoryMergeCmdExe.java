package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.command.MergeCommitCmd;
import com.mgg.exp.store.app.service.InventoryMergeAppService;
import com.mgg.exp.store.domain.deduction.valueobject.MergeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryMergeCmdExe {

    private final InventoryMergeAppService mergeAppService;

    public MergeResult execute(MergeCommitCmd cmd) {
        return mergeAppService.mergeCommit(cmd.getLockOrderId());
    }
}
