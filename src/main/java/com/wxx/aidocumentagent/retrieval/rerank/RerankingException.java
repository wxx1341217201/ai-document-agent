package com.wxx.aidocumentagent.retrieval.rerank;

import java.util.Objects;

/** 仅携带安全错误码的重排异常。 */
public final class RerankingException extends RuntimeException {

    private final RerankingErrorCode errorCode;

    public RerankingException(RerankingErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode不能为空").defaultMessage());
        this.errorCode = errorCode;
    }

    public RerankingException(RerankingErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode不能为空").defaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public RerankingErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return errorCode.retryable();
    }
}
