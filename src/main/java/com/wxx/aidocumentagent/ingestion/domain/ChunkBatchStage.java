package com.wxx.aidocumentagent.ingestion.domain;

/**
 * M08 的单个 batch 先完成向量索引，再完成关键词索引。INDEX 仅兼容 M06 已发布的旧消息。
 */
public enum ChunkBatchStage {
    INDEX,
    VECTOR_INDEX,
    KEYWORD_INDEX;

    public ChunkBatchStage normalized() {
        return this == INDEX ? VECTOR_INDEX : this;
    }

    /** 用于识别已经被状态机推进过的旧消息，绝不把未来阶段消息当作合法当前工作。 */
    public boolean isAfter(ChunkBatchStage other) {
        return normalized().ordinal() > other.normalized().ordinal();
    }
}
