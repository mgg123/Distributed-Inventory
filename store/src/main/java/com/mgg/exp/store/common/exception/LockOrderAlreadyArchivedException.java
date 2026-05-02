package com.mgg.exp.store.common.exception;

import com.mgg.exp.store.common.enums.ErrorCodeEnum;

public class LockOrderAlreadyArchivedException extends InventoryException {

    public LockOrderAlreadyArchivedException() {
        super(ErrorCodeEnum.LOCK_ORDER_ALREADY_ARCHIVED);
    }

    public LockOrderAlreadyArchivedException(String message) {
        super(ErrorCodeEnum.LOCK_ORDER_ALREADY_ARCHIVED, message);
    }
}
