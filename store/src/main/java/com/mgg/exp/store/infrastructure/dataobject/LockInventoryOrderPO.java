package com.mgg.exp.store.infrastructure.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("lock_inventory_order")
public class LockInventoryOrderPO implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private Long skuId;
    private Integer lockQuantity;
    private String bucketInfo;
    private LocalDateTime expireTime;
    private String status;
    private String idempotentKey;
    private Boolean mergeCompleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
