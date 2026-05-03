package com.mgg.exp.store.adapter.dto.command;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmergencyUnlockCmd {

    @NotNull(message = "skuId is required")
    private Long skuId;

    private Boolean force = false;
}
