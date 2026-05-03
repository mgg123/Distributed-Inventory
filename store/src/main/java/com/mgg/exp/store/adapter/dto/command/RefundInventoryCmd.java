package com.mgg.exp.store.adapter.dto.command;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RefundInventoryCmd {

    @NotBlank(message = "orderId is required")
    private String orderId;

    @NotNull(message = "skuId is required")
    private Long skuId;

    @NotNull(message = "refundQuantity is required")
    @Min(value = 1, message = "refundQuantity must be positive")
    private Integer refundQuantity;

    private String refundRequestId;
}
