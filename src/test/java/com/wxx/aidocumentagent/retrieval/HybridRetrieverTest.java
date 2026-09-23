package com.wxx.aidocumentagent.retrieval;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordHit;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.keyword.KeywordQuery;
import com.wxx.aidocumentagent.retrieval.rerank.NoOpReranker;
import com.wxx.aidocumentagent.retrieval.rerank.RankedChunk;
import com.wxx.aidocumentagent.retrieval.rerank.Reranker;
import com.wxx.aidocumentagent.retrieval.rerank.RerankingApplicationService;
import com.wxx.aidocumentagent.retrieval.rerank.RerankingProperties;
import com.wxx.aidocumentagent.vector.ChunkVector;
import com.wxx.aidocumentagent.vector.VectorHit;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class HybridRetrieverTest {

    private static final long KNOWLEDGE_BASE_ID = 9L;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    void 按排名而非不可比原始分数执行加权Rrf融合() {
        RetrievalProperties properties = properties();
        properties.setVectorWeight(2D);
        properties.setKeywordWeight(1D);
        HybridRetriever retriever = retriever(
                vectorIndex(query -> List.of(vectorHit(101L, 201L, "vector-first", 0.01D),
                        vectorHit(102L, 202L, "vector-second", 0.99D))),
                keywordIndex(query -> List.of(keywordHit(102L, 202L, "keyword-first", 0.02D),
                        keywordHit(103L, 203L, "keyword-second", 0.98D))), properties);

        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "error code", 2, 2, 3));

        assertThat(result.degraded()).isFalse();
        assertThat(result.unavailableChannels()).isEmpty();
        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactly(102L, 101L, 103L);
        RetrievedChunk duplicateHit = result.chunks().getFirst();
        assertThat(duplicateHit.vectorRank()).isEqualTo(2);
        assertThat(duplicateHit.vectorScore()).isEqualTo(0.99D);
        assertThat(duplicateHit.keywordRank()).isEqualTo(1);
        assertThat(duplicateHit.keywordScore()).isEqualTo(0.02D);
        assertThat(duplicateHit.hitChannels()).containsExactly(RetrievalChannel.VECTOR, RetrievalChannel.KEYWORD);
        assertThat(duplicateHit.fusedScore()).isCloseTo(2D / 62D + 1D / 61D, offset(0.000000000001D));
        assertThat(result.chunks().get(1).fusedScore()).isCloseTo(2D / 61D, offset(0.000000000001D));
    }

    @Test
    void 按chunkId去重并保留两个通道的排名与分数() {
        HybridRetriever retriever = retriever(
                vectorIndex(query -> List.of(vectorHit(101L, 201L, "first occurrence", 0.9D),
                        vectorHit(101L, 201L, "duplicate occurrence", 0.8D),
                        vectorHit(102L, 202L, "another", 0.7D))),
                keywordIndex(query -> List.of(keywordHit(101L, 201L, "keyword occurrence", 12D))), properties());

        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 3, 1, 3));

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactly(101L, 102L);
        RetrievedChunk chunk = result.chunks().getFirst();
        assertThat(chunk.content()).isEqualTo("first occurrence");
        assertThat(chunk.vectorRank()).isEqualTo(1);
        assertThat(chunk.vectorScore()).isEqualTo(0.9D);
        assertThat(chunk.keywordRank()).isEqualTo(1);
        assertThat(chunk.keywordScore()).isEqualTo(12D);
    }

    @Test
    void 两路空结果返回非降级空列表() {
        HybridRetriever retriever = retriever(vectorIndex(query -> List.of()), keywordIndex(query -> List.of()), properties());

        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "no match", 1, 1, 1));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.degraded()).isFalse();
        assertThat(result.unavailableChannels()).isEmpty();
    }

    @Test
    void 向量通道失败时降级到关键词通道并记录元数据() {
        HybridRetriever retriever = retriever(vectorIndex(query -> {
            throw new IllegalStateException("vector unavailable");
        }), keywordIndex(query -> List.of(keywordHit(101L, 201L, "keyword fallback", 4D))), properties());

        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 1, 1, 1));

        assertThat(result.degraded()).isTrue();
        assertThat(result.unavailableChannels()).containsExactly(RetrievalChannel.VECTOR);
        assertThat(result.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.hitChannels()).containsExactly(RetrievalChannel.KEYWORD);
            assertThat(chunk.vectorRank()).isNull();
            assertThat(chunk.keywordRank()).isEqualTo(1);
        });
    }

    @Test
    void 两路失败时抛出明确且不携带下游细节的业务错误() {
        HybridRetriever retriever = retriever(vectorIndex(query -> {
            throw new IllegalStateException("qdrant password=secret");
        }), keywordIndex(query -> {
            throw new IllegalStateException("elasticsearch password=secret");
        }), properties());

        assertThatThrownBy(() -> retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 1, 1, 1)))
                .isInstanceOfSatisfying(HybridRetrievalException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(RetrievalErrorCode.RETRIEVAL_ALL_CHANNELS_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void 单路超时会取消等待并降级到及时完成的另一通道() {
        RetrievalProperties properties = properties();
        properties.setVectorTimeout(Duration.ofMillis(50));
        properties.setKeywordTimeout(Duration.ofMillis(300));
        properties.setTotalTimeout(Duration.ofMillis(300));
        HybridRetriever retriever = retriever(vectorIndex(query -> blockUntilInterrupted()),
                keywordIndex(query -> List.of(keywordHit(101L, 201L, "timely keyword", 1D))), properties);

        long startedAt = System.nanoTime();
        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 1, 1, 1));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(result.degraded()).isTrue();
        assertThat(result.unavailableChannels()).containsExactly(RetrievalChannel.VECTOR);
        assertThat(result.chunks()).singleElement().extracting(RetrievedChunk::chunkId).isEqualTo(101L);
    }

    @Test
    void 总超时预算不会依次等待两个通道的单路超时() {
        RetrievalProperties properties = properties();
        properties.setVectorTimeout(Duration.ofSeconds(2));
        properties.setKeywordTimeout(Duration.ofSeconds(2));
        properties.setTotalTimeout(Duration.ofMillis(50));
        HybridRetriever retriever = retriever(vectorIndex(query -> blockUntilInterrupted()),
                keywordIndex(query -> blockUntilInterruptedKeyword()), properties);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 1, 1, 1)))
                .isInstanceOf(HybridRetrievalException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 跨知识库命中会被融合层再次过滤且两个索引都收到同一知识库边界() {
        AtomicReference<VectorQuery> vectorQuery = new AtomicReference<>();
        AtomicReference<KeywordQuery> keywordQuery = new AtomicReference<>();
        HybridRetriever retriever = retriever(vectorIndex(query -> {
            vectorQuery.set(query);
            return List.of(vectorHit(9001L, 901L, 99L, "foreign vector", 0.99D),
                    vectorHit(1001L, 201L, "own vector", 0.9D));
        }), keywordIndex(query -> {
            keywordQuery.set(query);
            return List.of(keywordHit(9002L, 902L, 99L, "foreign keyword", 12D),
                    keywordHit(1002L, 202L, "own keyword", 10D));
        }), properties());

        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 2, 2, 2));

        assertThat(vectorQuery.get().knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(keywordQuery.get().knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactlyInAnyOrder(1001L, 1002L);
        assertThat(result.chunks()).extracting(RetrievedChunk::knowledgeBaseId)
                .containsOnly(KNOWLEDGE_BASE_ID);
    }

    @Test
    void 融合分数相同时按chunkId稳定排序并按最终topK截断() {
        HybridRetriever retriever = retriever(vectorIndex(query -> List.of(vectorHit(200L, 202L, "vector", 0.9D))),
                keywordIndex(query -> List.of(keywordHit(100L, 201L, "keyword", 4D))), properties());

        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 1, 1, 1));

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactly(100L);
    }

    @Test
    void 使用配置默认topK构建统一查询() {
        RetrievalProperties properties = properties();
        properties.setVectorTopK(3);
        properties.setKeywordTopK(4);
        properties.setFinalTopK(2);
        AtomicReference<VectorQuery> vectorQuery = new AtomicReference<>();
        AtomicReference<KeywordQuery> keywordQuery = new AtomicReference<>();
        HybridRetriever retriever = retriever(vectorIndex(query -> {
            vectorQuery.set(query);
            return List.of();
        }), keywordIndex(query -> {
            keywordQuery.set(query);
            return List.of();
        }), properties);

        retriever.retrieve(KNOWLEDGE_BASE_ID, "configured query");

        assertThat(vectorQuery.get().topK()).isEqualTo(3);
        assertThat(keywordQuery.get().topK()).isEqualTo(4);
    }

    @Test
    void 将CrossEncoder结果接入Rrf候选并保留原始Rrf排名() {
        RerankingProperties rerankingProperties = new RerankingProperties();
        rerankingProperties.setEnabled(true);
        Reranker fakeReranker = (query, candidates, topN) -> List.of(
                new RankedChunk(candidates.get(1), 2, 1, 0.95D),
                new RankedChunk(candidates.getFirst(), 1, 2, 0.15D));
        RerankingApplicationService rerankingService = new RerankingApplicationService(rerankingProperties,
                fakeReranker, new NoOpReranker());
        HybridRetriever retriever = new HybridRetriever(
                vectorIndex(query -> List.of(vectorHit(101L, 201L, "vector candidate", 0.9D))),
                keywordIndex(query -> List.of(keywordHit(102L, 202L, "keyword candidate", 5D))), properties(),
                executor, rerankingService);

        RetrievalResult result = retriever.retrieve(new RetrievalQuery(KNOWLEDGE_BASE_ID, "query", 1, 1, 2));

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactly(102L, 101L);
        assertThat(result.rankedChunks()).extracting(RankedChunk::rrfRank).containsExactly(2, 1);
        assertThat(result.rankedChunks()).extracting(RankedChunk::rerankScore).containsExactly(0.95D, 0.15D);
        assertThat(result.rerankingApplied()).isTrue();
        assertThat(result.rerankingDegraded()).isFalse();
    }

    private HybridRetriever retriever(VectorIndex vectorIndex, KeywordIndex keywordIndex, RetrievalProperties properties) {
        return new HybridRetriever(vectorIndex, keywordIndex, properties, executor);
    }

    private RetrievalProperties properties() {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setRrfK(60);
        properties.setVectorTimeout(Duration.ofSeconds(2));
        properties.setKeywordTimeout(Duration.ofSeconds(2));
        properties.setTotalTimeout(Duration.ofSeconds(3));
        return properties;
    }

    private VectorIndex vectorIndex(Function<VectorQuery, List<VectorHit>> search) {
        return new VectorIndex() {
            @Override
            public void upsert(List<ChunkVector> vectors) {
            }

            @Override
            public List<VectorHit> search(VectorQuery query) {
                return search.apply(query);
            }

            @Override
            public void deleteByDocument(long knowledgeBaseId, long documentId) {
            }
        };
    }

    private KeywordIndex keywordIndex(Function<KeywordQuery, List<KeywordHit>> search) {
        return new KeywordIndex() {
            @Override
            public void upsert(List<KeywordDocument> chunks) {
            }

            @Override
            public List<KeywordHit> search(KeywordQuery query) {
                return search.apply(query);
            }

            @Override
            public void deleteByDocument(long knowledgeBaseId, long documentId) {
            }
        };
    }

    private VectorHit vectorHit(long chunkId, long documentId, String content, double score) {
        return vectorHit(chunkId, documentId, KNOWLEDGE_BASE_ID, content, score);
    }

    private VectorHit vectorHit(long chunkId, long documentId, long knowledgeBaseId, String content, double score) {
        return new VectorHit("point-" + chunkId, chunkId, documentId, knowledgeBaseId, 0, 1, 1,
                "a".repeat(64), "test-embedding", content, score);
    }

    private KeywordHit keywordHit(long chunkId, long documentId, String content, double score) {
        return keywordHit(chunkId, documentId, KNOWLEDGE_BASE_ID, content, score);
    }

    private KeywordHit keywordHit(long chunkId, long documentId, long knowledgeBaseId, String content, double score) {
        return new KeywordHit(chunkId, knowledgeBaseId, documentId, content, "source document", "section", 1, 1,
                "a".repeat(64), score);
    }

    private List<VectorHit> blockUntilInterrupted() {
        try {
            new CountDownLatch(1).await();
            return List.of();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test vector search interrupted", exception);
        }
    }

    private List<KeywordHit> blockUntilInterruptedKeyword() {
        try {
            new CountDownLatch(1).await();
            return List.of();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test keyword search interrupted", exception);
        }
    }
}
