package com.mgg.exp.store.infrastructure.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("deduction_detail")
public class DeductionDetailPO implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private Long skuId;
    private Integer quantity;
    private String deductPath;
    private Integer bucketIndex;
    private String status;
    private String orderId;
    private String lockOrderId;
    private String mergeBatchId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
