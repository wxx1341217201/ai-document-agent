package com.wxx.aidocumentagent.ingestion.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M06 的占位端口：只验证并交接已持久化的 batch，不尝试连接 Qdrant 或 Elasticsearch。
 */
public final class NoOpChunkBatchIndexingProcessor implements ChunkBatchIndexingProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoOpChunkBatchIndexingProcessor.class);

    @Override
    public void process(ChunkBatchIndexingRequest request) {
        LOGGER.debug("M06 batch调度完成，等待M07/M08索引处理器接入: batchId={}, chunks={}",
                request.batchId(), request.chunks().size());
    }
}
