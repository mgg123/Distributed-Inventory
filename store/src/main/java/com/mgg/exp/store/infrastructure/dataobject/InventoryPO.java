package com.mgg.exp.store.infrastructure.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("inventory")
public class InventoryPO implements Serializable {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Integer sq;
    private Integer wq;
    private Integer oq;
    private Integer lq;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
