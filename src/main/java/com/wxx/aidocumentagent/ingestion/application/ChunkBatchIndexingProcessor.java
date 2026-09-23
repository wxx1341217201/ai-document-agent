package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;

/** M08 通过持久化 stage 选择唯一实际索引器，不改动队列调度和状态机边界。 */
public interface ChunkBatchIndexingProcessor {

    void process(ChunkBatchStage stage, ChunkBatchIndexingRequest request);
}
