package com.wxx.aidocumentagent.retrieval;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/** M09 只暴露可安全返回给调用方的检索错误。 */
public enum RetrievalErrorCode implements ErrorCode {

    RETRIEVAL_ALL_CHANNELS_FAILED("RETRIEVAL_ALL_CHANNELS_FAILED", HttpStatus.SERVICE_UNAVAILABLE,
            "向量和关键词检索均不可用，请稍后重试");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    RetrievalErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
