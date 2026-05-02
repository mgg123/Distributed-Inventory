package com.mgg.exp.store.common.exception;

import com.mgg.exp.store.common.enums.ErrorCodeEnum;

public class CompensateMergeFailedException extends InventoryException {

    public CompensateMergeFailedException() {
        super(ErrorCodeEnum.INTERNAL_ERROR);
    }

    public CompensateMergeFailedException(String message) {
        super(ErrorCodeEnum.INTERNAL_ERROR, message);
    }
}
