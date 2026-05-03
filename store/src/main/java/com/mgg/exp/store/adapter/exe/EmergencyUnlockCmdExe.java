package com.mgg.exp.store.adapter.exe;

import com.mgg.exp.store.adapter.dto.command.EmergencyUnlockCmd;
import com.mgg.exp.store.app.service.EmergencyAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmergencyUnlockCmdExe {

    private final EmergencyAppService emergencyAppService;

    public void execute(EmergencyUnlockCmd cmd) {
        boolean force = cmd.getForce() != null && cmd.getForce();
        emergencyAppService.emergencyUnlock(cmd.getSkuId(), force);
    }
}
