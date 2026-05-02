package com.mgg.exp.store.domain.deduction.valueobject;

public final class MergeResult {

    private final boolean success;
    private final String errorCode;

    private MergeResult(boolean success, String errorCode) {
        this.success = success;
        this.errorCode = errorCode;
    }

    public static MergeResult success() {
        return new MergeResult(true, null);
    }

    public static MergeResult noPending() {
        return new MergeResult(true, null);
    }

    public static MergeResult failed(String errorCode) {
        return new MergeResult(false, errorCode);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
