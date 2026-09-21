package com.wxx.aidocumentagent.document.application;

import org.springframework.core.io.InputStreamSource;

/**
 * 与 HTTP MultipartFile 抽象解耦的上传输入。
 */
public record UploadDocumentCommand(
        String originalName,
        String contentType,
        long sizeBytes,
        InputStreamSource content) {
}
