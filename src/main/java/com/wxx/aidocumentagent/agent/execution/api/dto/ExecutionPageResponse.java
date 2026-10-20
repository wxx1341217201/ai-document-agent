package com.wxx.aidocumentagent.agent.execution.api.dto;

import java.util.List;

public record ExecutionPageResponse(List<ExecutionSummaryResponse> content, int page, int size,
                                     long totalElements, int totalPages) {
}
