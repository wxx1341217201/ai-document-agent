package com.wxx.aidocumentagent.common.api;

import org.springframework.http.HttpStatus;

/**
 * 定义稳定的 API 错误码及其 HTTP 表示。
 */
public interface ErrorCode {

    String code();

    HttpStatus status();

    String defaultMessage();
}
