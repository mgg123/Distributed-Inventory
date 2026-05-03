package com.mgg.exp.store.adapter.dto.query;

import lombok.Data;

@Data
public class InventoryVO {

    private Long skuId;
    private Integer sq;
    private Integer wq;
    private Integer oq;
    private Integer lq;
    private Integer availableQuantity;
}
