package com.wxx.aidocumentagent.knowledgebase.api.dto;

import java.util.List;

/**
 * 显式分页协议，不暴露 Spring Data 的 Page 实现。
 */
public record KnowledgeBasePageResponse(
        List<KnowledgeBaseResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
