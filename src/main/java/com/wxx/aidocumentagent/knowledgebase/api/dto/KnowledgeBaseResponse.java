package com.wxx.aidocumentagent.knowledgebase.api.dto;

import java.time.LocalDateTime;

/**
 * 知识库的 REST 响应模型。
 */
public record KnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
