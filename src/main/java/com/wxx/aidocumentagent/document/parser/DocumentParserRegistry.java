package com.wxx.aidocumentagent.document.parser;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.springframework.stereotype.Component;

/**
 * 根据标准化文档类型分发解析器，并在启动时拒绝同类型的重复实现。
 */
@Component
public final class DocumentParserRegistry {

    private final Map<DocumentType, DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parserImplementations) {
        EnumMap<DocumentType, DocumentParser> registeredParsers = new EnumMap<>(DocumentType.class);
        for (DocumentParser parser : parserImplementations) {
            for (DocumentType type : DocumentType.values()) {
                if (parser.supports(type)) {
                    DocumentParser existing = registeredParsers.putIfAbsent(type, parser);
                    if (existing != null) {
                        throw new IllegalStateException("同一文档类型不能注册多个解析器: " + type);
                    }
                }
            }
        }
        this.parsers = Map.copyOf(registeredParsers);
    }

    public DocumentParser parserFor(DocumentType type) {
        Objects.requireNonNull(type, "type不能为空");
        DocumentParser parser = parsers.get(type);
        if (parser == null) {
            throw new BusinessException(DocumentErrorCode.PARSE_UNSUPPORTED_TYPE);
        }
        return parser;
    }

    public ParsedDocument parse(DocumentSource source) {
        Objects.requireNonNull(source, "source不能为空");
        return parserFor(source.documentType()).parse(source);
    }
}
