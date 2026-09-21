package com.wxx.aidocumentagent.document.parser;

/**
 * 将一种标准化文档类型转换为统一文本表示的端口。
 */
public interface DocumentParser {

    boolean supports(DocumentType type);

    ParsedDocument parse(DocumentSource source);
}
