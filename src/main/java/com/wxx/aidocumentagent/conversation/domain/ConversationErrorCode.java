package com.wxx.aidocumentagent.conversation.domain;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/** 会话模块的稳定错误码。 */
public enum ConversationErrorCode implements ErrorCode {

    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND", HttpStatus.NOT_FOUND, "会话不存在"),
    CONVERSATION_ANSWERING_UNAVAILABLE("CONVERSATION_ANSWERING_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
            "会话回答服务暂不可用");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    ConversationErrorCode(String code, HttpStatus status, String defaultMessage) {
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
