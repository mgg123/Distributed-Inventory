package com.mgg.exp.store.domain.refund.valueobject;

public final class RefundResult {

    private final boolean success;
    private final String refundId;
    private final String errorCode;

    private RefundResult(boolean success, String refundId, String errorCode) {
        this.success = success;
        this.refundId = refundId;
        this.errorCode = errorCode;
    }

    public static RefundResult success(String refundId) {
        return new RefundResult(true, refundId, null);
    }

    public static RefundResult failed(String errorCode) {
        return new RefundResult(false, null, errorCode);
    }

    public static RefundResult skipped(String reason) {
        return new RefundResult(true, null, reason);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getRefundId() {
        return refundId;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
