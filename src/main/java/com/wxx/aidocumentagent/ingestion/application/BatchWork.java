package com.wxx.aidocumentagent.ingestion.application;

/** 已由数据库状态机独占的 batch 工作单元。 */
public record BatchWork(
        String batchId,
        String jobId,
        long knowledgeBaseId,
        long documentId,
        int chunkFrom,
        int chunkTo) {
}
