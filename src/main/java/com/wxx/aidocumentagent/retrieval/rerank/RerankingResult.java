package com.wxx.aidocumentagent.retrieval.rerank;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.retrieval.RetrievedChunk;

/** 重排阶段的结果、是否真正应用模型，以及安全降级和耗时元数据。 */
public record RerankingResult(
        List<RankedChunk> rankedChunks,
        boolean applied,
        boolean degraded,
        Duration elapsed) {

    public RerankingResult {
        rankedChunks = rankedChunks == null ? List.of() : List.copyOf(rankedChunks);
        elapsed = Objects.requireNonNull(elapsed, "elapsed不能为空");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed不能为负数");
        }
        if (applied && degraded) {
            throw new IllegalArgumentException("已应用的重排不能同时标记为降级");
        }
        for (int index = 0; index < rankedChunks.size(); index++) {
            RankedChunk chunk = Objects.requireNonNull(rankedChunks.get(index), "rankedChunk不能为空");
            if (chunk.rerankRank() != index + 1) {
                throw new IllegalArgumentException("rerankRank必须与输出顺序连续一致");
            }
            if (applied != (chunk.rerankScore() != null)) {
                throw new IllegalArgumentException("模型重排状态与rerankScore不一致");
            }
        }
    }

    public static RerankingResult rrfFallback(List<RetrievedChunk> candidates, int topN, boolean degraded,
                                               Duration elapsed) {
        Objects.requireNonNull(candidates, "candidates不能为空");
        if (topN < 1) {
            throw new IllegalArgumentException("topN必须大于0");
        }
        List<RankedChunk> ranked = new ArrayList<>(Math.min(candidates.size(), topN));
        for (int index = 0; index < candidates.size() && index < topN; index++) {
            ranked.add(RankedChunk.rrf(index + 1, candidates.get(index)));
        }
        return new RerankingResult(ranked, false, degraded, elapsed);
    }
}
