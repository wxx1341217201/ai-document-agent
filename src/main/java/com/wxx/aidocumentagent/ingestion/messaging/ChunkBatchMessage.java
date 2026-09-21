package com.wxx.aidocumentagent.ingestion.messaging;

import java.util.UUID;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;

/** chunk batch 消息：正文仍由消费者按知识库边界从数据库读取。 */
public record ChunkBatchMessage(
        UUID eventId,
        UUID jobId,
        UUID batchId,
        long documentId,
        long knowledgeBaseId,
        int chunkFrom,
        int chunkTo,
        ChunkBatchStage stage,
        int attempt,
        int schemaVersion) {

    public static final int SCHEMA_VERSION = 1;

    public void validate() {
        if (eventId == null || jobId == null || batchId == null || documentId <= 0 || knowledgeBaseId <= 0
                || chunkFrom < 0 || chunkTo < chunkFrom || stage != ChunkBatchStage.INDEX || attempt < 0
                || schemaVersion != SCHEMA_VERSION) {
            throw new IngestionMessageValidationException("chunk batch消息字段不合法");
        }
    }
}
