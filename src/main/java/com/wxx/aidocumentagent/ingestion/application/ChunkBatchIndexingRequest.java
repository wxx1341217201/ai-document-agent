package com.wxx.aidocumentagent.ingestion.application;

import java.util.List;
import java.util.Map;

/** 传给索引端口的数据始终带 knowledgeBaseId 与 batchId，供后续索引实现做幂等写入。 */
public record ChunkBatchIndexingRequest(
        String batchId,
        String jobId,
        long knowledgeBaseId,
        long documentId,
        int chunkFrom,
        int chunkTo,
        List<IndexableChunk> chunks) {

    public ChunkBatchIndexingRequest {
        chunks = List.copyOf(chunks);
    }

    public record IndexableChunk(
            long id,
            int chunkIndex,
            String content,
            String contentHash,
            int tokenCount,
            Integer pageFrom,
            Integer pageTo,
            String sectionTitle,
            Map<String, String> metadata) {

        public IndexableChunk {
            metadata = Map.copyOf(metadata);
        }
    }
}
