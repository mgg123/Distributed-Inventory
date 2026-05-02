package com.mgg.exp.store.infrastructure.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("refund_detail")
public class RefundDetailPO implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private Long skuId;
    private Integer refundQuantity;
    private String deductPath;
    private String status;
    private String orderId;
    private String refDetailId;
    private String refundRequestId;
    private LocalDateTime createTime;
}
