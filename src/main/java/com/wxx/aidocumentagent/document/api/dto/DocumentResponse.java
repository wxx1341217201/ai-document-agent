package com.wxx.aidocumentagent.document.api.dto;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;

/**
 * 已上传文档的 REST 元数据；刻意不返回内部存储键。
 */
public record DocumentResponse(
        Long id,
        Long knowledgeBaseId,
        String originalName,
        String contentType,
        String extension,
        long sizeBytes,
        String sha256,
        DocumentStatus status,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
