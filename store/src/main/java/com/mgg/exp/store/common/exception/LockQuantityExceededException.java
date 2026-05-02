package com.mgg.exp.store.common.exception;

import com.mgg.exp.store.common.enums.ErrorCodeEnum;

public class LockQuantityExceededException extends InventoryException {

    public LockQuantityExceededException() {
        super(ErrorCodeEnum.LOCK_QUANTITY_EXCEEDED);
    }

    public LockQuantityExceededException(String message) {
        super(ErrorCodeEnum.LOCK_QUANTITY_EXCEEDED, message);
    }
}
