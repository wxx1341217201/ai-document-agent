package com.wxx.aidocumentagent.document.api.dto;

import java.util.List;

/**
 * 文档元数据的显式分页协议。
 */
public record DocumentPageResponse(
        List<DocumentResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
