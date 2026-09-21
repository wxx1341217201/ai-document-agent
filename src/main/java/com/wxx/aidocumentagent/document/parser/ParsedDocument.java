package com.wxx.aidocumentagent.document.parser;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 各格式解析器共用的结构化文本结果。
 */
public record ParsedDocument(
        String text,
        List<ParsedSection> sections,
        Map<String, String> metadata,
        List<ParsedPageMapping> pageMappings,
        List<ParseWarning> warnings) {

    public ParsedDocument {
        Objects.requireNonNull(text, "text不能为空");
        sections = sections == null ? List.of() : List.copyOf(sections);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        pageMappings = pageMappings == null ? List.of() : List.copyOf(pageMappings);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        validateRanges(text, sections, pageMappings);
    }

    /**
     * 按需返回一个段落、页面或表格在完整文本中的内容。
     */
    public String textOf(ParsedSection section) {
        Objects.requireNonNull(section, "section不能为空");
        return text.substring(section.startOffset(), section.endOffset());
    }

    private static void validateRanges(String text, List<ParsedSection> sections,
                                       List<ParsedPageMapping> pageMappings) {
        for (ParsedSection section : sections) {
            if (section.endOffset() > text.length()) {
                throw new IllegalArgumentException("段落文本区间超出完整文本范围");
            }
        }
        for (ParsedPageMapping pageMapping : pageMappings) {
            if (pageMapping.endOffset() > text.length()) {
                throw new IllegalArgumentException("页码映射文本区间超出完整文本范围");
            }
        }
    }
}
