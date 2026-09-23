package com.wxx.aidocumentagent.ingestion.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;

/** 将持久化的 stage 精确路由到一个实现，避免两个索引阶段在同一次消息消费中并行推进。 */
public final class StagedChunkBatchIndexingProcessor implements ChunkBatchIndexingProcessor {

    private final Map<ChunkBatchStage, ChunkBatchStageProcessor> processors;

    public StagedChunkBatchIndexingProcessor(ChunkBatchStageProcessor... processors) {
        Map<ChunkBatchStage, ChunkBatchStageProcessor> mapped = new EnumMap<>(ChunkBatchStage.class);
        for (ChunkBatchStageProcessor processor : List.of(processors)) {
            ChunkBatchStage stage = processor.stage().normalized();
            if (stage == ChunkBatchStage.INDEX || mapped.putIfAbsent(stage, processor) != null) {
                throw new IllegalArgumentException("chunk batch索引阶段处理器配置不合法");
            }
        }
        if (!mapped.containsKey(ChunkBatchStage.VECTOR_INDEX) || !mapped.containsKey(ChunkBatchStage.KEYWORD_INDEX)) {
            throw new IllegalArgumentException("向量和关键词索引阶段都必须配置");
        }
        this.processors = Map.copyOf(mapped);
    }

    @Override
    public void process(ChunkBatchStage stage, ChunkBatchIndexingRequest request) {
        ChunkBatchStage normalized = stage.normalized();
        ChunkBatchStageProcessor processor = processors.get(normalized);
        if (processor == null) {
            throw new IllegalArgumentException("不存在可处理的chunk batch索引阶段: " + normalized);
        }
        processor.process(request);
    }
}
