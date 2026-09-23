package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 索引显式禁用时的占位端口，不尝试连接 Qdrant 或 Elasticsearch。
 */
public final class NoOpChunkBatchIndexingProcessor implements ChunkBatchIndexingProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoOpChunkBatchIndexingProcessor.class);

    @Override
    public void process(ChunkBatchStage stage, ChunkBatchIndexingRequest request) {
        LOGGER.debug("索引已禁用，跳过batch阶段: batchId={}, stage={}, chunks={}",
                request.batchId(), stage, request.chunks().size());
    }
}
