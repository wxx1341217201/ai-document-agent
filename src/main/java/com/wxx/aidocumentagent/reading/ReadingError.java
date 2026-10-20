package com.wxx.aidocumentagent.reading;

import com.wxx.aidocumentagent.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ReadingError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "阅读任务不存在"),
    INVALID_SCOPE(HttpStatus.BAD_REQUEST, "请选择同一知识库中已就绪的 1 到 20 份资料"),
    CONFLICT(HttpStatus.CONFLICT, "内容或任务状态已变化，请刷新后重试"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "阅读请求无效"),
    SOURCE_CHANGED(HttpStatus.CONFLICT, "资料已变化，需要重新核验相关分析");

    private final HttpStatus status;
    private final String message;
    ReadingError(HttpStatus status, String message) { this.status = status; this.message = message; }
    public String code() { return "READING_" + name(); }
    public HttpStatus status() { return status; }
    public String defaultMessage() { return message; }
}
