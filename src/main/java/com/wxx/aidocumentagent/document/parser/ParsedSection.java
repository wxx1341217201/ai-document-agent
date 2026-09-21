package com.wxx.aidocumentagent.document.parser;

import java.util.Map;
import java.util.Objects;

/**
 * 原文中可追溯的一段文本区间；文本通过 {@link ParsedDocument#textOf(ParsedSection)} 按需取得，
 * 避免在结果中重复保留整篇文档。
 */
public record ParsedSection(
        int sequence,
        Kind kind,
        Integer pageNumber,
        int startOffset,
        int endOffset,
        Map<String, String> attributes) {

    public ParsedSection {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence必须大于0");
        }
        Objects.requireNonNull(kind, "kind不能为空");
        if (pageNumber != null && pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber必须大于0");
        }
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("文本区间不合法");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public enum Kind {
        PAGE,
        PARAGRAPH,
        TABLE
    }
}
