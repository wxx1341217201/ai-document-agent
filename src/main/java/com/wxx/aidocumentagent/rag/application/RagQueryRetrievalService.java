package com.wxx.aidocumentagent.rag.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.retrieval.HybridRetrievalException;
import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import com.wxx.aidocumentagent.retrieval.rerank.RankedChunk;

/** 每次最多三个独立检索，共用截止时间；轮流保留各子问题的高排名证据。 */
public final class RagQueryRetrievalService {

    private final RagRetrievalGateway gateway;
    private final RagProperties properties;
    private final ExecutorService executor;
    private final RagRetrievalQueryPlanner planner = new RagRetrievalQueryPlanner();

    public RagQueryRetrievalService(RagRetrievalGateway gateway, RagProperties properties, ExecutorService executor) {
        this.gateway = Objects.requireNonNull(gateway);
        this.properties = Objects.requireNonNull(properties);
        this.executor = executor;
    }

    public Outcome retrieve(long knowledgeBaseId, String question, List<RagConversationTurn> history,
                            int topK, boolean rerank) {
        List<String> queries = planner.plan(question, history, properties.isQueryExpansionEnabled(),
                properties.getMaxRetrievalQueries());
        int candidates = Math.max(topK, properties.getRetrievalCandidateTopK());
        long deadline = System.nanoTime() + properties.getQueryExpansionTimeout().toNanos();
        List<Future<RetrievalResult>> tasks = new ArrayList<>();
        List<RetrievalResult> results = new ArrayList<>();
        List<String> successfulQueries = new ArrayList<>();
        int failed = 0;
        try {
            for (String query : queries) {
                if (executor == null) {
                    try {
                        var result = gateway.retrieve(knowledgeBaseId, query, candidates, rerank);
                        results.add(result);
                        successfulQueries.add(query);
                    }
                    catch (RuntimeException failure) { failed++; }
                }
                else {
                    tasks.add(executor.submit(() -> gateway.retrieve(knowledgeBaseId, query, candidates, rerank)));
                }
            }
            for (int index = 0; index < tasks.size(); index++) {
                Future<RetrievalResult> task = tasks.get(index);
                try {
                    results.add(task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                    successfulQueries.add(queries.get(index));
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new HybridRetrievalException();
                }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                       | java.util.concurrent.CancellationException failure) {
                    task.cancel(true);
                    failed++;
                }
            }
        }
        finally {
            tasks.forEach(task -> { if (!task.isDone()) task.cancel(true); });
        }
        if (results.isEmpty()) throw new HybridRetrievalException();
        return new Outcome(merge(knowledgeBaseId, results, successfulQueries, topK),
                new RagRetrievalTrace(RagRetrievalQueryPlanner.VERSION, queries, failed,
                        results.stream().flatMap(result -> result.rankedChunks().stream()).map(hit ->
                                new RagRetrievalTrace.Candidate(hit.chunkId(), hit.chunk().documentId(), hit.rerankRank())).toList()),
                merge(knowledgeBaseId, results, successfulQueries, candidates).chunks());
    }

    private RetrievalResult merge(long knowledgeBaseId, List<RetrievalResult> results, List<String> queries, int topK) {
        var originals = results;
        results = java.util.stream.IntStream.range(0, originals.size()).mapToObj(index -> {
            var result = originals.get(index);
            // Keep the fused/reranked rank as the prior; direct lexical coverage breaks broad topic ties.
            var sorted = result.rankedChunks().stream().sorted(java.util.Comparator.comparingDouble(
                    (RankedChunk hit) -> EvidenceRelevance.score(queries.get(Math.min(index, queries.size() - 1)), hit.chunk().content())
                            + 4.0 / hit.rerankRank()).reversed()).toList();
            var ranked = java.util.stream.IntStream.range(0, sorted.size()).mapToObj(rank -> new RankedChunk(
                    sorted.get(rank).chunk(), sorted.get(rank).rrfRank(), rank + 1, sorted.get(rank).rerankScore())).toList();
            return new RetrievalResult(ranked.stream().map(RankedChunk::chunk).toList(), result.degraded(), result.unavailableChannels(), ranked,
                    result.rerankingApplied(), result.rerankingDegraded(), result.rerankingElapsed());
        }).toList();
        Map<Long, RankedChunk> selected = new LinkedHashMap<>();
        int maximum = results.stream().mapToInt(result -> result.rankedChunks().size()).max().orElse(0);
        for (int rank = 0; rank < maximum && selected.size() < topK; rank++) {
            for (RetrievalResult result : results) {
                if (rank < result.rankedChunks().size() && selected.size() < topK) {
                    RankedChunk hit = result.rankedChunks().get(rank);
                    if (hit.knowledgeBaseId() == knowledgeBaseId) selected.putIfAbsent(hit.chunkId(), hit);
                }
            }
        }
        EnumSet<RetrievalChannel> unavailable = EnumSet.noneOf(RetrievalChannel.class);
        results.forEach(result -> unavailable.addAll(result.unavailableChannels()));
        boolean rerankDegraded = results.stream().anyMatch(RetrievalResult::rerankingDegraded);
        boolean rerankApplied = !rerankDegraded && results.stream().allMatch(RetrievalResult::rerankingApplied);
        List<RankedChunk> ranked = new ArrayList<>();
        for (RankedChunk hit : selected.values()) {
            ranked.add(new RankedChunk(hit.chunk(), hit.rrfRank(), ranked.size() + 1,
                    rerankApplied ? hit.rerankScore() : null));
        }
        return new RetrievalResult(ranked.stream().map(RankedChunk::chunk).toList(), !unavailable.isEmpty(),
                List.copyOf(unavailable), ranked, rerankApplied, rerankDegraded,
                results.stream().map(RetrievalResult::rerankingElapsed).reduce(Duration.ZERO, Duration::plus));
    }

    public record Outcome(RetrievalResult retrieval, RagRetrievalTrace trace,
                          List<com.wxx.aidocumentagent.retrieval.RetrievedChunk> candidates) {
        public Outcome(RetrievalResult retrieval, RagRetrievalTrace trace) { this(retrieval, trace, retrieval.chunks()); }
        public Outcome { candidates = List.copyOf(candidates); }
        public boolean degraded() {
            return retrieval.degraded() || retrieval.rerankingDegraded() || trace.failedQueries() > 0;
        }
    }
}
