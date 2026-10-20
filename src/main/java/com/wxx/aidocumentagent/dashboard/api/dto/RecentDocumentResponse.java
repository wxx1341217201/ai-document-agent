package com.wxx.aidocumentagent.dashboard.api.dto;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;

public record RecentDocumentResponse(Long id, Long knowledgeBaseId, String originalName, String extension,
                                     DocumentStatus status, LocalDateTime updatedAt) {
}
