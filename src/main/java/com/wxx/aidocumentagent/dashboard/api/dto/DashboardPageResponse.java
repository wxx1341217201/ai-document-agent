package com.wxx.aidocumentagent.dashboard.api.dto;

import java.util.List;

public record DashboardPageResponse<T>(List<T> content, int page, int size,
                                       long totalElements, int totalPages) {
}
