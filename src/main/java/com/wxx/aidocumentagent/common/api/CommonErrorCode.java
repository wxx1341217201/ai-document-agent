package com.wxx.aidocumentagent.common.api;

import org.springframework.http.HttpStatus;

/**
 * 所有模块共用的错误码；模块专属错误码可实现 {@link ErrorCode}。
 */
public enum CommonErrorCode implements ErrorCode {

    BAD_REQUEST("BAD_REQUEST", HttpStatus.BAD_REQUEST, "请求参数不合法"),
    VALIDATION_ERROR("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT("CONFLICT", HttpStatus.CONFLICT, "资源状态冲突"),
    FILE_TOO_LARGE("FILE_TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE, "文件超过大小限制"),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    CommonErrorCode(String code, HttpStatus status, String defaultMessage) {
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
