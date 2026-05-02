package com.mgg.exp.store.common.enums;

import lombok.Getter;

@Getter
public enum ErrorCodeEnum {

    SUCCESS(0, "success"),
    INSUFFICIENT_STOCK(101001, "库存不足"),
    STOCK_NOT_FOUND(101002, "库存记录不存在"),
    DEDUCTION_DUPLICATE(101003, "扣减幂等命中"),
    LOCK_QUANTITY_EXCEEDED(102001, "可用额度不足，无法锁库存"),
    LOCK_ORDER_NOT_FOUND(102002, "锁库存单据不存在"),
    LOCK_ORDER_NOT_ACTIVE(102003, "锁库存单据非活跃状态"),
    LOCK_IDEMPOTENT_CONFLICT(102004, "锁库存幂等命中"),
    LOCK_ORDER_ALREADY_ARCHIVED(102005, "锁库存幂等键对应单据已归档"),
    MERGE_IN_PROGRESS(103001, "合并提交正在进行中"),
    MERGE_NO_PENDING(103002, "无待合并明细"),
    MERGE_SQ_INSUFFICIENT(103003, "合并提交sq或lq不足"),
    ROUTE_NOT_FOUND(104001, "活跃路由不存在"),
    ROUTE_BUCKET_INVALID(104002, "分桶索引已失效"),
    PARAM_INVALID(199001, "参数校验失败"),
    INTERNAL_ERROR(199002, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCodeEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
