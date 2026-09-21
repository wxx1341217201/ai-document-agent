package com.wxx.aidocumentagent.ingestion.api.dto;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;

/** 手动重试接口返回的受限任务状态，不暴露 outbox 或异常堆栈。 */
public record IngestionJobResponse(
        String jobId,
        long documentId,
        long knowledgeBaseId,
        IngestionJobStatus status,
        int attempt,
        int totalBatchCount,
        int completedBatchCount,
        int failedBatchCount,
        String errorCode,
        String errorMessage,
        LocalDateTime enqueuedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt) {
}
