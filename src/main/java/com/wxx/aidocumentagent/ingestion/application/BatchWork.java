package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;

/** 已由数据库状态机独占的 batch 工作单元。 */
public record BatchWork(
        String batchId,
        String jobId,
        long knowledgeBaseId,
        long documentId,
        int chunkFrom,
        int chunkTo,
        ChunkBatchStage stage) {
}
