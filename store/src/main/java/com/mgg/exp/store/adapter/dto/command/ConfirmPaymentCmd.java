package com.mgg.exp.store.adapter.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfirmPaymentCmd {

    @NotBlank(message = "orderId is required")
    private String orderId;

    @NotNull(message = "skuId is required")
    private Long skuId;
}
