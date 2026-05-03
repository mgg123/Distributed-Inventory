package com.mgg.exp.store.adapter.web;

import com.mgg.exp.store.adapter.dto.CommonResponse;
import com.mgg.exp.store.common.exception.InsufficientStockException;
import com.mgg.exp.store.common.exception.InventoryException;
import com.mgg.exp.store.common.exception.LockOrderAlreadyArchivedException;
import com.mgg.exp.store.common.exception.LockQuantityExceededException;
import com.mgg.exp.store.common.exception.MergeCommitFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CommonResponse<Void> handleInsufficientStock(InsufficientStockException e) {
        log.warn("insufficient stock: {}", e.getMessage());
        return CommonResponse.failed(e.getErrorCode().getCode(), e.getMessage());
    }

    @ExceptionHandler(LockQuantityExceededException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CommonResponse<Void> handleLockQuantityExceeded(LockQuantityExceededException e) {
        log.warn("lock quantity exceeded: {}", e.getMessage());
        return CommonResponse.failed(e.getErrorCode().getCode(), e.getMessage());
    }

    @ExceptionHandler(LockOrderAlreadyArchivedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CommonResponse<Void> handleLockOrderAlreadyArchived(LockOrderAlreadyArchivedException e) {
        log.warn("lock order already archived: {}", e.getMessage());
        return CommonResponse.failed(e.getErrorCode().getCode(), e.getMessage());
    }

    @ExceptionHandler(MergeCommitFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public CommonResponse<Void> handleMergeCommitFailed(MergeCommitFailedException e) {
        log.error("merge commit failed: {}", e.getMessage());
        return CommonResponse.failed(e.getErrorCode().getCode(), e.getMessage());
    }

    @ExceptionHandler(InventoryException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public CommonResponse<Void> handleInventoryException(InventoryException e) {
        log.error("inventory exception: {}", e.getMessage(), e);
        return CommonResponse.failed(e.getErrorCode().getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResponse<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("validation error: {}", message);
        return CommonResponse.failed(199001, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public CommonResponse<Void> handleException(Exception e) {
        log.error("unexpected error: {}", e.getMessage(), e);
        return CommonResponse.failed(199002, "系统内部错误");
    }
}
