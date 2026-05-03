package com.mgg.exp.store.adapter.dto.command;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeductInventoryCmd {

    @NotBlank(message = "orderId is required")
    private String orderId;

    @NotNull(message = "skuId is required")
    private Long skuId;

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be positive")
    private Integer quantity;

    private String lockOrderId;
}
