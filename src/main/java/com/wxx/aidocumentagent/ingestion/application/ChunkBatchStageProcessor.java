package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;

/** 一个实际索引阶段的处理器；阶段选择由应用层状态机决定而非由 RabbitMQ 投递次数决定。 */
public interface ChunkBatchStageProcessor {

    ChunkBatchStage stage();

    void process(ChunkBatchIndexingRequest request);
}
