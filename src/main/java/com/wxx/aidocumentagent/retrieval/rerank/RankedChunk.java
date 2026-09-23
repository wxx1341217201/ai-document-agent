package com.wxx.aidocumentagent.retrieval.rerank;

import java.util.Objects;

import com.wxx.aidocumentagent.retrieval.RetrievedChunk;

/**
 * 重排输出保留原始 RRF 位次，并给出最终位次及（可用时的）Cross-Encoder 分数。
 * 当重排关闭或发生降级时，rerankScore 为 {@code null}，但 rerankRank 仍表示最终顺序。
 */
public record RankedChunk(
        RetrievedChunk chunk,
        int rrfRank,
        int rerankRank,
        Double rerankScore) {

    public RankedChunk {
        chunk = Objects.requireNonNull(chunk, "chunk不能为空");
        if (rrfRank < 1) {
            throw new IllegalArgumentException("rrfRank必须大于0");
        }
        if (rerankRank < 1) {
            throw new IllegalArgumentException("rerankRank必须大于0");
        }
        if (rerankScore != null && !Double.isFinite(rerankScore)) {
            throw new IllegalArgumentException("rerankScore必须是有限值");
        }
    }

    public static RankedChunk rrf(int rrfRank, RetrievedChunk chunk) {
        return new RankedChunk(chunk, rrfRank, rrfRank, null);
    }

    public long chunkId() {
        return chunk.chunkId();
    }

    public long knowledgeBaseId() {
        return chunk.knowledgeBaseId();
    }
}
