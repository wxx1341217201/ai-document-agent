package com.wxx.aidocumentagent.vector;

/** 已按 knowledgeBaseId 二次校验后的语义检索结果。 */
public record VectorHit(
        String pointId,
        long chunkId,
        long documentId,
        long knowledgeBaseId,
        int chunkIndex,
        Integer pageFrom,
        Integer pageTo,
        String contentHash,
        String embeddingModel,
        String content,
        double score) {
}
