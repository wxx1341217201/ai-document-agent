package com.wxx.aidocumentagent.document.parser;

import java.util.Locale;
import java.util.Optional;

/**
 * 解析层使用的标准化文档类型，与上传阶段保存的扩展名一一对应。
 */
public enum DocumentType {

    TXT("txt"),
    PDF("pdf"),
    DOCX("docx");

    private final String extension;

    DocumentType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static Optional<DocumentType> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        for (DocumentType type : values()) {
            if (type.extension.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
