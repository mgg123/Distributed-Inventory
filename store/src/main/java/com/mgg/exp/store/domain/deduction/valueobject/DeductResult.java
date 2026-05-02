package com.mgg.exp.store.domain.deduction.valueobject;

public final class DeductResult {

    private final boolean success;
    private final String detailId;
    private final int luaResult;
    private final String errorCode;

    private DeductResult(boolean success, String detailId, int luaResult, String errorCode) {
        this.success = success;
        this.detailId = detailId;
        this.luaResult = luaResult;
        this.errorCode = errorCode;
    }

    public static DeductResult success(String detailId, int luaResult) {
        return new DeductResult(true, detailId, luaResult, null);
    }

    public static DeductResult insufficient() {
        return new DeductResult(false, null, 0, "INSUFFICIENT_STOCK");
    }

    public static DeductResult degraded(String detailId) {
        return new DeductResult(true, detailId, -1, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDetailId() {
        return detailId;
    }

    public int getLuaResult() {
        return luaResult;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isBucketExhausted() {
        return luaResult == 2;
    }
}
