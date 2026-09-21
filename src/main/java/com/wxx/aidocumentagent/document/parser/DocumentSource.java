package com.wxx.aidocumentagent.document.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.springframework.core.io.InputStreamSource;

/**
 * 解析输入只持有可按需打开的流，不在模型中缓存整份原始文件。
 */
public record DocumentSource(
        DocumentType documentType,
        String sourceName,
        InputStreamSource content) {

    public DocumentSource {
        Objects.requireNonNull(documentType, "documentType不能为空");
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName不能为空");
        }
        Objects.requireNonNull(content, "content不能为空");
    }

    public InputStream openStream() throws IOException {
        InputStream inputStream = content.getInputStream();
        if (inputStream == null) {
            throw new IOException("文档输入流为空");
        }
        return inputStream;
    }
}
