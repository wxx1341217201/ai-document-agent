package com.wxx.aidocumentagent.vector;

import java.util.Objects;

/** 只暴露稳定错误码与安全摘要，底层 SDK 异常仅保留作运行时 cause。 */
public class VectorIndexException extends RuntimeException {

    private final VectorIndexErrorCode errorCode;

    public VectorIndexException(VectorIndexErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public VectorIndexException(VectorIndexErrorCode errorCode, Throwable cause) {
        this(errorCode, errorCode.defaultMessage(), cause);
    }

    public VectorIndexException(VectorIndexErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, null);
    }

    public VectorIndexException(VectorIndexErrorCode errorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "向量错误码不能为空");
    }

    public VectorIndexErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return errorCode.retryable();
    }
}
