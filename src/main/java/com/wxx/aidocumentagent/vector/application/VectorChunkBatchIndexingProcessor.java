package com.wxx.aidocumentagent.vector.application;

import java.util.List;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingRequest;
import com.wxx.aidocumentagent.ingestion.application.ChunkBatchStageProcessor;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import com.wxx.aidocumentagent.vector.ChunkVector;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * M06 已用 batchId + VECTOR_INDEX + JPA version 独占工作；此处理器仅在成功 upsert 后返回，
 * 由生命周期服务把该 vector stage 合法推进为 COMPLETED。稳定 point ID 使处理中断后的重放继续为幂等 upsert。
 */
@Component
@ConditionalOnProperty(prefix = "app.vector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorChunkBatchIndexingProcessor implements ChunkBatchStageProcessor {

    private final VectorIndex vectorIndex;
    private final VectorProperties properties;

    public VectorChunkBatchIndexingProcessor(VectorIndex vectorIndex, VectorProperties properties) {
        this.vectorIndex = vectorIndex;
        this.properties = properties;
    }

    @Override
    public ChunkBatchStage stage() {
        return ChunkBatchStage.VECTOR_INDEX;
    }

    @Override
    public void process(ChunkBatchIndexingRequest request) {
        if (request.knowledgeBaseId() <= 0 || request.documentId() <= 0) {
            throw new IllegalArgumentException("batch向量索引范围不合法");
        }
        List<ChunkVector> vectors = request.chunks().stream()
                .map(chunk -> new ChunkVector(chunk.id(), request.documentId(), request.knowledgeBaseId(),
                        chunk.chunkIndex(), chunk.pageFrom(), chunk.pageTo(), chunk.contentHash(),
                        properties.getEmbedding().getModel(), chunk.content()))
                .toList();
        vectorIndex.upsert(vectors);
    }
}
