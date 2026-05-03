package com.mgg.exp.store.adapter.dto.command;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MergeCommitCmd {

    @NotBlank(message = "lockOrderId is required")
    private String lockOrderId;
}
