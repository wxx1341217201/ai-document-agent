package com.wxx.aidocumentagent.retrieval.rerank;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakingRerankerTest {

    @Test
    void 连续可重试失败达到阈值后停止调用远端() {
        RerankingProperties properties = new RerankingProperties();
        properties.setCircuitFailureThreshold(2);
        AtomicInteger calls = new AtomicInteger();
        Reranker unavailable = (query, candidates, topN) -> {
            calls.incrementAndGet();
            throw new RerankingException(RerankingErrorCode.REMOTE_UNAVAILABLE);
        };
        CircuitBreakingReranker reranker = new CircuitBreakingReranker(unavailable, properties);
        List<RetrievedChunk> candidates = List.of(new RetrievedChunk(101L, 9L, 201L, "content", 1, 1,
                1, 0.9D, null, null, 0.01D, List.of(RetrievalChannel.VECTOR)));

        assertThatThrownBy(() -> reranker.rerank("query", candidates, 1)).isInstanceOf(RerankingException.class);
        assertThatThrownBy(() -> reranker.rerank("query", candidates, 1)).isInstanceOf(RerankingException.class);
        assertThatThrownBy(() -> reranker.rerank("query", candidates, 1))
                .isInstanceOfSatisfying(RerankingException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RerankingErrorCode.CIRCUIT_OPEN));

        assertThat(calls).hasValue(2);
    }
}
