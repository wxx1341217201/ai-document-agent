package com.wxx.aidocumentagent.retrieval.rerank;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M10 的应用层编排：显式校验知识库边界、限制外发候选，并在任何远端失败时安全回退 RRF。
 */
public final class RerankingApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RerankingApplicationService.class);

    private final RerankingProperties properties;
    private final Reranker reranker;
    private final Reranker noOpReranker;

    public RerankingApplicationService(RerankingProperties properties, Reranker reranker, Reranker noOpReranker) {
        this.properties = Objects.requireNonNull(properties, "rerankingProperties不能为空");
        this.reranker = Objects.requireNonNull(reranker, "reranker不能为空");
        this.noOpReranker = Objects.requireNonNull(noOpReranker, "noOpReranker不能为空");
    }

    public RerankingResult rerank(long knowledgeBaseId, String query, List<RetrievedChunk> rrfCandidates, int topN) {
        if (knowledgeBaseId <= 0L) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        NoOpReranker.requireRequest(query, rrfCandidates, topN);
        long startedAt = System.nanoTime();
        List<RetrievedChunk> scopedCandidates = candidatesForKnowledgeBase(knowledgeBaseId, rrfCandidates);

        if (!properties.isEnabled() || scopedCandidates.isEmpty()) {
            return rrfFallback(query, scopedCandidates, topN, false, elapsedSince(startedAt), "disabled");
        }

        List<RetrievedChunk> boundedCandidates = scopedCandidates.stream()
                .limit(properties.getMaxCandidates())
                .toList();
        try {
            List<RankedChunk> reranked = reranker.rerank(query, boundedCandidates, topN);
            List<RankedChunk> canonical = validateAndCanonicalize(reranked, boundedCandidates, topN);
            RerankingResult result = new RerankingResult(canonical, true, false, elapsedSince(startedAt));
            logResult("applied", result);
            return result;
        }
        catch (RerankingException exception) {
            return rrfFallback(query, scopedCandidates, topN, true, elapsedSince(startedAt),
                    exception.getErrorCode().name());
        }
        catch (RuntimeException exception) {
            return rrfFallback(query, scopedCandidates, topN, true, elapsedSince(startedAt),
                    RerankingErrorCode.INTERNAL_FAILURE.name());
        }
    }

    private List<RetrievedChunk> candidatesForKnowledgeBase(long knowledgeBaseId, List<RetrievedChunk> candidates) {
        Map<Long, RetrievedChunk> scoped = new LinkedHashMap<>();
        for (RetrievedChunk candidate : candidates) {
            // 即使上游索引出现错误，也绝不能把另一知识库的文本发给外部重排服务。
            if (candidate.knowledgeBaseId() == knowledgeBaseId) {
                scoped.putIfAbsent(candidate.chunkId(), candidate);
            }
        }
        return List.copyOf(scoped.values());
    }

    private List<RankedChunk> validateAndCanonicalize(List<RankedChunk> reranked,
                                                        List<RetrievedChunk> requestedCandidates, int topN) {
        if (reranked == null) {
            throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
        }
        int expectedSize = Math.min(topN, requestedCandidates.size());
        if (reranked.size() != expectedSize) {
            throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
        }

        Map<Long, CandidateAtRrfRank> candidatesById = new LinkedHashMap<>();
        for (int index = 0; index < requestedCandidates.size(); index++) {
            RetrievedChunk candidate = requestedCandidates.get(index);
            candidatesById.put(candidate.chunkId(), new CandidateAtRrfRank(candidate, index + 1));
        }

        Map<Long, Boolean> returnedIds = new LinkedHashMap<>();
        List<RankedChunk> canonical = new ArrayList<>(reranked.size());
        for (int index = 0; index < reranked.size(); index++) {
            RankedChunk ranked = reranked.get(index);
            if (ranked == null || ranked.rerankScore() == null || !Double.isFinite(ranked.rerankScore())) {
                throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
            }
            CandidateAtRrfRank input = candidatesById.get(ranked.chunkId());
            if (input == null || returnedIds.putIfAbsent(ranked.chunkId(), Boolean.TRUE) != null
                    || ranked.rrfRank() != input.rrfRank() || ranked.rerankRank() != index + 1) {
                throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
            }
            // 使用请求前的规范 chunk，而不是相信 reranker 回传的内容，避免 ID 正确但内容错位。
            canonical.add(new RankedChunk(input.chunk(), input.rrfRank(), index + 1, ranked.rerankScore()));
        }
        return List.copyOf(canonical);
    }

    private RerankingResult rrfFallback(String query, List<RetrievedChunk> candidates, int topN, boolean degraded,
                                         Duration elapsed, String outcome) {
        List<RankedChunk> fallback = noOpReranker.rerank(query, candidates, topN);
        RerankingResult result = new RerankingResult(fallback, false, degraded, elapsed);
        logResult(outcome, result);
        return result;
    }

    private void logResult(String outcome, RerankingResult result) {
        List<String> rankTransitions = result.rankedChunks().stream()
                .map(chunk -> chunk.chunkId() + ":" + chunk.rrfRank() + "->" + chunk.rerankRank() + ":"
                        + (chunk.rerankScore() == null ? "-" : chunk.rerankScore()))
                .toList();
        if (result.degraded()) {
            LOGGER.warn("Cross-encoder reranking outcome={}, applied={}, transitions={}, elapsedMs={}", outcome,
                    result.applied(), rankTransitions, result.elapsed().toMillis());
        }
        else {
            LOGGER.info("Cross-encoder reranking outcome={}, applied={}, transitions={}, elapsedMs={}", outcome,
                    result.applied(), rankTransitions, result.elapsed().toMillis());
        }
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }

    private record CandidateAtRrfRank(RetrievedChunk chunk, int rrfRank) {
    }
}
