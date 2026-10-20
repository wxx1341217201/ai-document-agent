package com.wxx.aidocumentagent.dashboard.api.dto;

import java.time.LocalDateTime;

public record RecentKnowledgeBaseResponse(Long id, String name, String description, LocalDateTime updatedAt) {
}
