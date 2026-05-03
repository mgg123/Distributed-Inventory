package com.mgg.exp.store.adapter.dto.command;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LockInventoryCmd {

    @NotNull(message = "skuId is required")
    private Long skuId;

    @NotNull(message = "lockQuantity is required")
    @Min(value = 1, message = "lockQuantity must be positive")
    private Integer lockQuantity;

    @NotBlank(message = "idempotentKey is required")
    private String idempotentKey;

    private Double reserveRatio;
}
