package com.wxx.aidocumentagent.document.parser;

/**
 * 可返回给后续摄取流程的非致命解析告警。
 */
public enum ParseWarningCode {
    EMPTY_PAGE,
    SUSPECTED_GARBLED_TEXT,
    TRUNCATED_TEXT
}
