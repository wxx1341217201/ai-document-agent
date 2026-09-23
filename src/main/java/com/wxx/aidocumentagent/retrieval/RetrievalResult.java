package com.wxx.aidocumentagent.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.retrieval.rerank.RankedChunk;

/**
 * 混合检索结果及安全的降级元数据。
 * unavailableChannels 只描述未能完成的通道，不携带底层服务错误文本。
 */
public record RetrievalResult(
        List<RetrievedChunk> chunks,
        boolean degraded,
        List<RetrievalChannel> unavailableChannels,
        List<RankedChunk> rankedChunks,
        boolean rerankingApplied,
        boolean rerankingDegraded,
        Duration rerankingElapsed) {

    /** 保留 M09 的构造方式：没有 M10 编排器时以 RRF 顺序作为最终输出。 */
    public RetrievalResult(List<RetrievedChunk> chunks, boolean degraded, List<RetrievalChannel> unavailableChannels) {
        this(chunks, degraded, unavailableChannels, rrfRankedChunks(chunks), false, false, Duration.ZERO);
    }

    public RetrievalResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        unavailableChannels = orderedChannels(unavailableChannels);
        rankedChunks = rankedChunks == null ? List.of() : List.copyOf(rankedChunks);
        rerankingElapsed = Objects.requireNonNull(rerankingElapsed, "rerankingElapsed不能为空");
        if (degraded != !unavailableChannels.isEmpty()) {
            throw new IllegalArgumentException("degraded必须与不可用通道元数据一致");
        }
        if (rerankingElapsed.isNegative()) {
            throw new IllegalArgumentException("rerankingElapsed不能为负数");
        }
        if (rerankingApplied && rerankingDegraded) {
            throw new IllegalArgumentException("已应用的重排不能同时标记为降级");
        }
        if (chunks.size() != rankedChunks.size()) {
            throw new IllegalArgumentException("最终chunk与重排chunk数量不一致");
        }
        for (int index = 0; index < chunks.size(); index++) {
            RankedChunk ranked = Objects.requireNonNull(rankedChunks.get(index), "rankedChunk不能为空");
            if (!chunks.get(index).equals(ranked.chunk()) || ranked.rerankRank() != index + 1
                    || rerankingApplied != (ranked.rerankScore() != null)) {
                throw new IllegalArgumentException("最终chunk与重排元数据不一致");
            }
        }
    }

    private static List<RankedChunk> rrfRankedChunks(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<RankedChunk> ranked = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            ranked.add(RankedChunk.rrf(index + 1, chunks.get(index)));
        }
        return List.copyOf(ranked);
    }

    private static List<RetrievalChannel> orderedChannels(List<RetrievalChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }
        if (channels.stream().anyMatch(channel -> channel == null)) {
            throw new IllegalArgumentException("不可用通道不能为空");
        }
        List<RetrievalChannel> ordered = new ArrayList<>(2);
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            if (channels.contains(channel)) {
                ordered.add(channel);
            }
        }
        if (ordered.size() != channels.size()) {
            throw new IllegalArgumentException("不可用通道不能重复");
        }
        return List.copyOf(ordered);
    }
}
