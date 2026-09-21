package com.wxx.aidocumentagent.document.parser;

import java.util.Objects;

/**
 * 不阻断解析结果使用的安全告警摘要。
 */
public record ParseWarning(ParseWarningCode code, String message, Integer pageNumber) {

    public ParseWarning {
        Objects.requireNonNull(code, "code不能为空");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message不能为空");
        }
        if (pageNumber != null && pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber必须大于0");
        }
    }
}
