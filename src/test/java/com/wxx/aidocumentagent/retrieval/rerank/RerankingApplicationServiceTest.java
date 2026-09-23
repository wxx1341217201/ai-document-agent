package com.wxx.aidocumentagent.retrieval.rerank;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RerankingApplicationServiceTest {

    private static final long KNOWLEDGE_BASE_ID = 9L;

    @Test
    void 开关关闭时不调用外部Reranker并保留Rrf顺序() {
        RerankingProperties properties = new RerankingProperties();
        AtomicInteger calls = new AtomicInteger();
        Reranker external = (query, candidates, topN) -> {
            calls.incrementAndGet();
            throw new AssertionError("关闭开关后不应调用外部reranker");
        };
        RerankingApplicationService service = service(properties, external);

        RerankingResult result = service.rerank(KNOWLEDGE_BASE_ID, "query", candidates(), 2);

        assertThat(calls).hasValue(0);
        assertThat(result.applied()).isFalse();
        assertThat(result.degraded()).isFalse();
        assertThat(result.rankedChunks()).extracting(RankedChunk::chunkId).containsExactly(101L, 102L);
        assertThat(result.rankedChunks()).extracting(RankedChunk::rerankScore).containsOnlyNulls();
    }

    @Test
    void 超时降级到完整Rrf顺序() {
        RerankingProperties properties = enabledProperties();
        Reranker timeout = (query, candidates, topN) -> {
            throw new RerankingException(RerankingErrorCode.REQUEST_TIMEOUT);
        };
        RerankingApplicationService service = service(properties, timeout);

        RerankingResult result = service.rerank(KNOWLEDGE_BASE_ID, "query", candidates(), 3);

        assertThat(result.applied()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.rankedChunks()).extracting(RankedChunk::chunkId).containsExactly(101L, 102L, 103L);
        assertThat(result.rankedChunks()).extracting(RankedChunk::rrfRank).containsExactly(1, 2, 3);
        assertThat(result.rankedChunks()).extracting(RankedChunk::rerankRank).containsExactly(1, 2, 3);
        assertThat(result.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void 部分无效响应同样降级到Rrf顺序() {
        RerankingProperties properties = enabledProperties();
        Reranker invalidResponse = (query, candidates, topN) -> {
            throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
        };
        RerankingApplicationService service = service(properties, invalidResponse);

        RerankingResult result = service.rerank(KNOWLEDGE_BASE_ID, "query", candidates(), 2);

        assertThat(result.applied()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.rankedChunks()).extracting(RankedChunk::chunkId).containsExactly(101L, 102L);
    }

    @Test
    void fakeReranker的Id重排使用原始候选内容且不会发生数组错位() {
        RerankingProperties properties = enabledProperties();
        Reranker fake = (query, input, topN) -> List.of(
                new RankedChunk(forgedChunk(102L, "wrong content for second"), 2, 1, 0.99D),
                new RankedChunk(forgedChunk(101L, "wrong content for first"), 1, 2, 0.11D));
        RerankingApplicationService service = service(properties, fake);

        RerankingResult result = service.rerank(KNOWLEDGE_BASE_ID, "query", candidates(), 2);

        assertThat(result.applied()).isTrue();
        assertThat(result.degraded()).isFalse();
        assertThat(result.rankedChunks()).extracting(RankedChunk::chunkId).containsExactly(102L, 101L);
        assertThat(result.rankedChunks()).extracting(value -> value.chunk().content())
                .containsExactly("original second", "original first");
        assertThat(result.rankedChunks()).extracting(RankedChunk::rerankScore).containsExactly(0.99D, 0.11D);
    }

    @Test
    void 仅将显式匹配知识库边界的候选交给外部服务() {
        RerankingProperties properties = enabledProperties();
        AtomicReference<List<RetrievedChunk>> requested = new AtomicReference<>();
        Reranker fake = (query, input, topN) -> {
            requested.set(List.copyOf(input));
            return List.of(new RankedChunk(input.getFirst(), 1, 1, 0.8D));
        };
        RerankingApplicationService service = service(properties, fake);
        RetrievedChunk foreign = new RetrievedChunk(999L, 99L, 199L, "foreign", 1, 1, 1, 0.5D,
                null, null, 0.01D, List.of(RetrievalChannel.VECTOR));

        RerankingResult result = service.rerank(KNOWLEDGE_BASE_ID, "query",
                List.of(candidates().getFirst(), foreign), 1);

        assertThat(requested.get()).extracting(RetrievedChunk::knowledgeBaseId).containsOnly(KNOWLEDGE_BASE_ID);
        assertThat(result.rankedChunks()).singleElement().extracting(RankedChunk::chunkId).isEqualTo(101L);
    }

    private RerankingApplicationService service(RerankingProperties properties, Reranker reranker) {
        return new RerankingApplicationService(properties, reranker, new NoOpReranker());
    }

    private RerankingProperties enabledProperties() {
        RerankingProperties properties = new RerankingProperties();
        properties.setEnabled(true);
        properties.setMaxCandidates(2);
        return properties;
    }

    private List<RetrievedChunk> candidates() {
        return List.of(chunk(101L, "original first"), chunk(102L, "original second"), chunk(103L, "original third"));
    }

    private RetrievedChunk forgedChunk(long chunkId, String content) {
        return chunk(chunkId, content);
    }

    private RetrievedChunk chunk(long chunkId, String content) {
        return new RetrievedChunk(chunkId, KNOWLEDGE_BASE_ID, chunkId + 100L, content, 1, 1, 1, 0.8D,
                null, null, 0.01D, List.of(RetrievalChannel.VECTOR));
    }
}
