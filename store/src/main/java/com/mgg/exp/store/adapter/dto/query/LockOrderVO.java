package com.mgg.exp.store.adapter.dto.query;

import lombok.Data;

@Data
public class LockOrderVO {

    private String lockOrderId;
    private Long skuId;
    private Integer lockQuantity;
    private String bucketInfo;
    private String expireTime;
    private String status;
    private String idempotentKey;
    private Boolean mergeCompleted;
}
