package com.wxx.aidocumentagent.chunking;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 可由部署环境调整的默认切分参数；最终窗口大小会由 M19 的评测结果决定。
 */
@Validated
@ConfigurationProperties(prefix = "app.chunking")
public class ChunkingProperties {

    @NotBlank
    private String strategy = ParagraphChunkingStrategy.STRATEGY_NAME;

    @NotNull
    private ChunkingUnit windowUnit = ChunkingUnit.TOKEN;

    @Min(1)
    private int chunkSize = 900;

    @Min(0)
    private int overlap = 120;

    @Min(1)
    private int persistenceBatchSize = 100;

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public ChunkingUnit getWindowUnit() {
        return windowUnit;
    }

    public void setWindowUnit(ChunkingUnit windowUnit) {
        this.windowUnit = windowUnit;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public int getPersistenceBatchSize() {
        return persistenceBatchSize;
    }

    public void setPersistenceBatchSize(int persistenceBatchSize) {
        this.persistenceBatchSize = persistenceBatchSize;
    }

    @AssertTrue(message = "app.chunking.overlap必须小于app.chunking.chunk-size")
    public boolean isOverlapSmallerThanChunkSize() {
        return overlap < chunkSize;
    }

    public ChunkingOptions toOptions() {
        return new ChunkingOptions(windowUnit, chunkSize, overlap);
    }
}
