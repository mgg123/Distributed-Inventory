package com.mgg.exp.store.adapter.dto.query;

import lombok.Data;

@Data
public class DeductionDetailVO {

    private String detailId;
    private String orderId;
    private Long skuId;
    private Integer quantity;
    private String deductPath;
    private String status;
    private String lockOrderId;
    private Integer bucketIndex;
    private String mergeBatchId;
}
