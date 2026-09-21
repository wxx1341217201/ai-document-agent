package com.wxx.aidocumentagent.knowledgebase.domain;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 知识库操作使用的稳定错误码。
 */
public enum KnowledgeBaseErrorCode implements ErrorCode {

    KNOWLEDGE_BASE_NOT_FOUND("KNOWLEDGE_BASE_NOT_FOUND", HttpStatus.NOT_FOUND, "知识库不存在"),
    KNOWLEDGE_BASE_NAME_CONFLICT("KNOWLEDGE_BASE_NAME_CONFLICT", HttpStatus.CONFLICT, "知识库名称已存在"),
    KNOWLEDGE_BASE_NOT_EMPTY("KNOWLEDGE_BASE_NOT_EMPTY", HttpStatus.CONFLICT, "知识库仍包含文档，无法删除");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    KnowledgeBaseErrorCode(String code, HttpStatus status, String defaultMessage) {
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
