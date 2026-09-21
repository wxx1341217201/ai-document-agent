package com.wxx.aidocumentagent.chunking.domain;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 切分模块的稳定错误码。
 */
public enum ChunkingErrorCode implements ErrorCode {

    CHUNKING_STRATEGY_NOT_FOUND("CHUNKING_STRATEGY_NOT_FOUND", HttpStatus.BAD_REQUEST, "不支持的文本切分策略");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    ChunkingErrorCode(String code, HttpStatus status, String defaultMessage) {
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
