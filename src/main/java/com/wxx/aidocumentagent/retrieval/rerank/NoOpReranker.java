package com.wxx.aidocumentagent.retrieval.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.retrieval.RetrievedChunk;

/** 本地或关闭重排时使用：严格保留输入的 RRF 顺序，不访问外部模型。 */
public final class NoOpReranker implements Reranker {

    @Override
    public List<RankedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        requireRequest(query, candidates, topN);
        List<RankedChunk> ranked = new ArrayList<>(Math.min(candidates.size(), topN));
        for (int index = 0; index < candidates.size() && index < topN; index++) {
            ranked.add(RankedChunk.rrf(index + 1, candidates.get(index)));
        }
        return List.copyOf(ranked);
    }

    static void requireRequest(String query, List<RetrievedChunk> candidates, int topN) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query不能为空");
        }
        Objects.requireNonNull(candidates, "candidates不能为空");
        if (topN < 1) {
            throw new IllegalArgumentException("topN必须大于0");
        }
        if (candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidates不能包含null");
        }
    }
}
