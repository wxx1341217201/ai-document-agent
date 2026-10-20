package com.wxx.aidocumentagent.document.api.dto;

import java.util.List;

public record DocumentChunkPageResponse(List<DocumentChunkResponse> content, int page, int size,
                                         long totalElements, int totalPages) {
}
