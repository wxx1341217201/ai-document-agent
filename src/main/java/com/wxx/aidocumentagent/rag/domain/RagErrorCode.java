package com.wxx.aidocumentagent.rag.domain;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/** 只向 API 暴露稳定且不含供应商异常详情的 RAG 错误码。 */
public enum RagErrorCode implements ErrorCode {

    CHAT_MODEL_TIMEOUT("RAG_CHAT_MODEL_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "问答模型响应超时，请稍后重试"),
    CHAT_MODEL_UNAVAILABLE("RAG_CHAT_MODEL_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
            "问答模型暂不可用，请稍后重试"),
    MODEL_CALL_AUDIT_FAILED("RAG_MODEL_CALL_AUDIT_FAILED", HttpStatus.SERVICE_UNAVAILABLE,
            "模型调用审计暂不可用，请稍后重试");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    RagErrorCode(String code, HttpStatus status, String defaultMessage) {
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
