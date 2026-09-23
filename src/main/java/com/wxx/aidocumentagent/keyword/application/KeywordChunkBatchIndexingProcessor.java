package com.wxx.aidocumentagent.keyword.application;

import java.util.List;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingRequest;
import com.wxx.aidocumentagent.ingestion.application.ChunkBatchStageProcessor;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordIndex;

/** 仅在向量阶段成功后运行的关键词索引阶段，ES _id 固定为 chunkId。 */
public final class KeywordChunkBatchIndexingProcessor implements ChunkBatchStageProcessor {

    private final KeywordIndex keywordIndex;

    public KeywordChunkBatchIndexingProcessor(KeywordIndex keywordIndex) {
        this.keywordIndex = keywordIndex;
    }

    @Override
    public ChunkBatchStage stage() {
        return ChunkBatchStage.KEYWORD_INDEX;
    }

    @Override
    public void process(ChunkBatchIndexingRequest request) {
        if (request.knowledgeBaseId() <= 0 || request.documentId() <= 0) {
            throw new IllegalArgumentException("batch关键词索引范围不合法");
        }
        List<KeywordDocument> documents = request.chunks().stream()
                .map(chunk -> new KeywordDocument(chunk.id(), request.knowledgeBaseId(), request.documentId(),
                        chunk.content(), request.documentTitle(), chunk.sectionTitle(), chunk.pageFrom(), chunk.pageTo(),
                        chunk.contentHash()))
                .toList();
        keywordIndex.upsert(documents);
    }
}
