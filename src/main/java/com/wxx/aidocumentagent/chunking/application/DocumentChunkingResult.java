package com.wxx.aidocumentagent.chunking.application;

import java.util.List;

import com.wxx.aidocumentagent.chunking.TextChunk;

/**
 * 一次替换切分的可审计结果，不包含模型推理或外部索引结果。
 */
public record DocumentChunkingResult(
        long knowledgeBaseId,
        long documentId,
        String strategyName,
        int replacedChunkCount,
        List<TextChunk> chunks) {

    public DocumentChunkingResult {
        chunks = List.copyOf(chunks);
    }
}
