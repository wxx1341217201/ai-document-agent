package com.wxx.aidocumentagent.agent.execution;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ExecutionErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "执行不存在"),
    REQUEST_KEY_CONFLICT(HttpStatus.CONFLICT, "幂等键已用于不同请求"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "执行状态不允许此操作"),
    EXECUTION_EXPIRED(HttpStatus.CONFLICT, "执行恢复期限已过"),
    STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "执行状态保存失败");

    private final HttpStatus status;
    private final String message;

    ExecutionErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public String code() { return name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String defaultMessage() { return message; }
}
