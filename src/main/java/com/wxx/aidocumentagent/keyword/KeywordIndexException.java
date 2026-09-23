package com.wxx.aidocumentagent.keyword;

import java.util.Objects;

/** 仅暴露稳定错误码和安全摘要，避免将 ES 响应正文写入任务状态。 */
public class KeywordIndexException extends RuntimeException {

    private final KeywordIndexErrorCode errorCode;

    public KeywordIndexException(KeywordIndexErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public KeywordIndexException(KeywordIndexErrorCode errorCode, Throwable cause) {
        this(errorCode, errorCode.defaultMessage(), cause);
    }

    public KeywordIndexException(KeywordIndexErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, null);
    }

    public KeywordIndexException(KeywordIndexErrorCode errorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "关键词索引错误码不能为空");
    }

    public KeywordIndexErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return errorCode.retryable();
    }
}
