package com.wxx.aidocumentagent.common.api;

import java.util.Objects;

/**
 * 可预期且能够安全返回给调用方的应用异常。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "错误码不能为空");
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "错误码不能为空");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
