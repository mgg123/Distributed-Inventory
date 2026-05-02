package com.mgg.exp.store.common.exception;

import com.mgg.exp.store.common.enums.ErrorCodeEnum;

public class MergeCommitFailedException extends InventoryException {

    public MergeCommitFailedException() {
        super(ErrorCodeEnum.MERGE_SQ_INSUFFICIENT);
    }

    public MergeCommitFailedException(String message) {
        super(ErrorCodeEnum.MERGE_SQ_INSUFFICIENT, message);
    }
}
