package com.wxx.aidocumentagent.retrieval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.wxx.aidocumentagent.keyword.KeywordHit;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.keyword.KeywordQuery;
import com.wxx.aidocumentagent.retrieval.rerank.RankedChunk;
import com.wxx.aidocumentagent.retrieval.rerank.RerankingApplicationService;
import com.wxx.aidocumentagent.retrieval.rerank.RerankingResult;
import com.wxx.aidocumentagent.vector.VectorHit;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorQuery;

/**
 * M09 的应用服务：并行发起向量与关键词检索，并以 Reciprocal Rank Fusion 合并结果。
 * 它不调用 ChatModel，也不会把下游异常正文带入返回结果。
 */
public final class HybridRetriever {

    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;
    private final RetrievalProperties properties;
    private final ExecutorService executor;
    private final RerankingApplicationService rerankingApplicationService;

    public HybridRetriever(VectorIndex vectorIndex, KeywordIndex keywordIndex, RetrievalProperties properties,
                           ExecutorService executor) {
        this(vectorIndex, keywordIndex, properties, executor, null);
    }

    public HybridRetriever(VectorIndex vectorIndex, KeywordIndex keywordIndex, RetrievalProperties properties,
                           ExecutorService executor, RerankingApplicationService rerankingApplicationService) {
        this.vectorIndex = Objects.requireNonNull(vectorIndex, "vectorIndex不能为空");
        this.keywordIndex = Objects.requireNonNull(keywordIndex, "keywordIndex不能为空");
        this.properties = Objects.requireNonNull(properties, "retrievalProperties不能为空");
        this.executor = Objects.requireNonNull(executor, "retrievalExecutor不能为空");
        this.rerankingApplicationService = rerankingApplicationService;
    }

    /** 使用部署配置中的各路 topK 和最终 topK。 */
    public RetrievalResult retrieve(long knowledgeBaseId, String query) {
        return retrieve(properties.defaultQuery(knowledgeBaseId, query));
    }

    public RetrievalResult retrieve(RetrievalQuery query) {
        return retrieve(query, true);
    }

    /**
     * 允许上层按请求关闭可选重排。检索与 RRF 仍保持不变，避免为了关闭重排而绕过混合检索边界校验。
     */
    public RetrievalResult retrieve(RetrievalQuery query, boolean rerank) {
        Objects.requireNonNull(query, "retrievalQuery不能为空");
        long deadlineNanos = System.nanoTime() + properties.getTotalTimeout().toNanos();

        ChannelTask<List<VectorHit>> vectorTask = submit(() -> {
            List<VectorHit> hits = vectorIndex.search(new VectorQuery(query.knowledgeBaseId(), query.query(),
                    query.vectorTopK(), properties.getVectorSimilarityThreshold()));
            return List.copyOf(hits);
        });
        ChannelTask<List<KeywordHit>> keywordTask = submit(() -> {
            List<KeywordHit> hits = keywordIndex.search(new KeywordQuery(query.knowledgeBaseId(), query.query(),
                    query.keywordTopK()));
            return List.copyOf(hits);
        });

        ChannelOutcome<List<VectorHit>> vector = await(vectorTask, properties.getVectorTimeout(), deadlineNanos);
        ChannelOutcome<List<KeywordHit>> keyword = await(keywordTask, properties.getKeywordTimeout(), deadlineNanos);
        cancelIfRunning(vectorTask);
        cancelIfRunning(keywordTask);

        if (!vector.successful() && !keyword.successful()) {
            throw new HybridRetrievalException();
        }

        List<RankedVectorHit> vectorHits = vector.successful()
                ? rankVectorHits(vector.value(), query.knowledgeBaseId()) : List.of();
        List<RankedKeywordHit> keywordHits = keyword.successful()
                ? rankKeywordHits(keyword.value(), query.knowledgeBaseId()) : List.of();
        List<RetrievedChunk> rrfCandidates = fuse(vectorHits, keywordHits).stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::fusedScore).reversed()
                        .thenComparingLong(RetrievedChunk::chunkId))
                .toList();
        RerankingResult reranking = !rerank || rerankingApplicationService == null
                ? RerankingResult.rrfFallback(rrfCandidates, query.finalTopK(), false, Duration.ZERO)
                : rerankingApplicationService.rerank(query.knowledgeBaseId(), query.query(), rrfCandidates,
                        query.finalTopK());
        List<RankedChunk> rankedChunks = reranking.rankedChunks();
        List<RetrievedChunk> chunks = rankedChunks.stream().map(RankedChunk::chunk).toList();

        List<RetrievalChannel> unavailableChannels = unavailableChannels(vector.successful(), keyword.successful());
        return new RetrievalResult(chunks, !unavailableChannels.isEmpty(), unavailableChannels, rankedChunks,
                reranking.applied(), reranking.degraded(), reranking.elapsed());
    }

    private <T> ChannelTask<T> submit(java.util.concurrent.Callable<T> task) {
        try {
            return ChannelTask.submitted(executor.submit(task));
        }
        catch (RejectedExecutionException exception) {
            return ChannelTask.failed();
        }
    }

    private <T> ChannelOutcome<T> await(ChannelTask<T> task, Duration channelTimeout, long deadlineNanos) {
        if (!task.submitted()) {
            return ChannelOutcome.failed();
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            task.future().cancel(true);
            return ChannelOutcome.failed();
        }
        long timeoutNanos = Math.min(channelTimeout.toNanos(), remainingNanos);
        try {
            return ChannelOutcome.succeeded(task.future().get(timeoutNanos, TimeUnit.NANOSECONDS));
        }
        catch (TimeoutException exception) {
            task.future().cancel(true);
            return ChannelOutcome.failed();
        }
        catch (InterruptedException exception) {
            task.future().cancel(true);
            Thread.currentThread().interrupt();
            return ChannelOutcome.failed();
        }
        catch (CancellationException | ExecutionException exception) {
            return ChannelOutcome.failed();
        }
    }

    private void cancelIfRunning(ChannelTask<?> task) {
        if (task.submitted() && !task.future().isDone()) {
            task.future().cancel(true);
        }
    }

    private List<RankedVectorHit> rankVectorHits(List<VectorHit> hits, long knowledgeBaseId) {
        Map<Long, RankedVectorHit> uniqueHits = new LinkedHashMap<>();
        for (VectorHit hit : hits) {
            if (isUsable(hit, knowledgeBaseId) && !uniqueHits.containsKey(hit.chunkId())) {
                uniqueHits.put(hit.chunkId(), new RankedVectorHit(hit, uniqueHits.size() + 1));
            }
        }
        return List.copyOf(uniqueHits.values());
    }

    private List<RankedKeywordHit> rankKeywordHits(List<KeywordHit> hits, long knowledgeBaseId) {
        Map<Long, RankedKeywordHit> uniqueHits = new LinkedHashMap<>();
        for (KeywordHit hit : hits) {
            if (isUsable(hit, knowledgeBaseId) && !uniqueHits.containsKey(hit.chunkId())) {
                uniqueHits.put(hit.chunkId(), new RankedKeywordHit(hit, uniqueHits.size() + 1));
            }
        }
        return List.copyOf(uniqueHits.values());
    }

    private boolean isUsable(VectorHit hit, long knowledgeBaseId) {
        return hit != null && hit.knowledgeBaseId() == knowledgeBaseId && hit.chunkId() > 0L && hit.documentId() > 0L
                && hit.content() != null && isPageRangeValid(hit.pageFrom(), hit.pageTo()) && Double.isFinite(hit.score());
    }

    private boolean isUsable(KeywordHit hit, long knowledgeBaseId) {
        return hit != null && hit.knowledgeBaseId() == knowledgeBaseId && hit.chunkId() > 0L && hit.documentId() > 0L
                && hit.content() != null && isPageRangeValid(hit.pageFrom(), hit.pageTo()) && Double.isFinite(hit.score());
    }

    private boolean isPageRangeValid(Integer pageFrom, Integer pageTo) {
        return (pageFrom == null && pageTo == null)
                || (pageFrom != null && pageTo != null && pageFrom >= 1 && pageTo >= pageFrom);
    }

    private List<RetrievedChunk> fuse(List<RankedVectorHit> vectorHits, List<RankedKeywordHit> keywordHits) {
        Map<Long, FusionCandidate> candidates = new LinkedHashMap<>();
        for (RankedVectorHit ranked : vectorHits) {
            candidates.computeIfAbsent(ranked.hit().chunkId(), ignored -> FusionCandidate.from(ranked.hit()))
                    .addVector(ranked.rank(), ranked.hit().score());
        }
        for (RankedKeywordHit ranked : keywordHits) {
            candidates.computeIfAbsent(ranked.hit().chunkId(), ignored -> FusionCandidate.from(ranked.hit()))
                    .addKeyword(ranked.rank(), ranked.hit().score());
        }
        return candidates.values().stream().map(candidate -> candidate.toRetrievedChunk(properties)).toList();
    }

    private List<RetrievalChannel> unavailableChannels(boolean vectorSuccessful, boolean keywordSuccessful) {
        List<RetrievalChannel> channels = new ArrayList<>(2);
        if (!vectorSuccessful) {
            channels.add(RetrievalChannel.VECTOR);
        }
        if (!keywordSuccessful) {
            channels.add(RetrievalChannel.KEYWORD);
        }
        return List.copyOf(channels);
    }

    private record ChannelTask<T>(Future<T> future) {

        static <T> ChannelTask<T> submitted(Future<T> future) {
            return new ChannelTask<>(future);
        }

        static <T> ChannelTask<T> failed() {
            return new ChannelTask<>(null);
        }

        boolean submitted() {
            return future != null;
        }
    }

    private record ChannelOutcome<T>(T value, boolean successful) {

        static <T> ChannelOutcome<T> succeeded(T value) {
            return new ChannelOutcome<>(value, true);
        }

        static <T> ChannelOutcome<T> failed() {
            return new ChannelOutcome<>(null, false);
        }
    }

    private record RankedVectorHit(VectorHit hit, int rank) {
    }

    private record RankedKeywordHit(KeywordHit hit, int rank) {
    }

    private static final class FusionCandidate {

        private final long chunkId;
        private final long knowledgeBaseId;
        private final long documentId;
        private final String content;
        private final Integer pageFrom;
        private final Integer pageTo;
        private Integer vectorRank;
        private Double vectorScore;
        private Integer keywordRank;
        private Double keywordScore;

        private FusionCandidate(long chunkId, long knowledgeBaseId, long documentId, String content,
                                Integer pageFrom, Integer pageTo) {
            this.chunkId = chunkId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.documentId = documentId;
            this.content = content;
            this.pageFrom = pageFrom;
            this.pageTo = pageTo;
        }

        static FusionCandidate from(VectorHit hit) {
            return new FusionCandidate(hit.chunkId(), hit.knowledgeBaseId(), hit.documentId(), hit.content(),
                    hit.pageFrom(), hit.pageTo());
        }

        static FusionCandidate from(KeywordHit hit) {
            return new FusionCandidate(hit.chunkId(), hit.knowledgeBaseId(), hit.documentId(), hit.content(),
                    hit.pageFrom(), hit.pageTo());
        }

        void addVector(int rank, double score) {
            vectorRank = rank;
            vectorScore = score;
        }

        void addKeyword(int rank, double score) {
            keywordRank = rank;
            keywordScore = score;
        }

        RetrievedChunk toRetrievedChunk(RetrievalProperties properties) {
            double fusedScore = 0D;
            List<RetrievalChannel> channels = new ArrayList<>(2);
            if (vectorRank != null) {
                fusedScore += properties.getVectorWeight() / (properties.getRrfK() + (double) vectorRank);
                channels.add(RetrievalChannel.VECTOR);
            }
            if (keywordRank != null) {
                fusedScore += properties.getKeywordWeight() / (properties.getRrfK() + (double) keywordRank);
                channels.add(RetrievalChannel.KEYWORD);
            }
            return new RetrievedChunk(chunkId, knowledgeBaseId, documentId, content, pageFrom, pageTo,
                    vectorRank, vectorScore, keywordRank, keywordScore, fusedScore, channels);
        }
    }
}
