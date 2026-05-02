package com.mgg.exp.store.common.exception;

import com.mgg.exp.store.common.enums.ErrorCodeEnum;

public class InsufficientStockException extends InventoryException {

    public InsufficientStockException() {
        super(ErrorCodeEnum.INSUFFICIENT_STOCK);
    }

    public InsufficientStockException(String message) {
        super(ErrorCodeEnum.INSUFFICIENT_STOCK, message);
    }
}
