package com.wxx.aidocumentagent.ingestion.application;

/** M07/M08 通过替换此端口接入向量与关键词索引，而不改动队列调度和状态机。 */
public interface ChunkBatchIndexingProcessor {

    void process(ChunkBatchIndexingRequest request);
}
