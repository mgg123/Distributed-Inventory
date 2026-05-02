package com.mgg.exp.store.common.exception;

import com.mgg.exp.store.common.enums.ErrorCodeEnum;
import lombok.Getter;

@Getter
public class InventoryException extends RuntimeException {

    private final ErrorCodeEnum errorCode;

    public InventoryException(ErrorCodeEnum errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public InventoryException(ErrorCodeEnum errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public InventoryException(ErrorCodeEnum errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
