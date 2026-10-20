package com.wxx.aidocumentagent.rag.application;

import java.util.List;

/** 实际检索问题和有界扩展统计；不包含模型隐藏推理或外部异常正文。 */
public record RagRetrievalTrace(String strategyVersion, List<String> queries, int failedQueries,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<Candidate> candidates) {
    public RagRetrievalTrace(String strategyVersion, List<String> queries, int failedQueries) {
        this(strategyVersion, queries, failedQueries, List.of());
    }
    public RagRetrievalTrace {
        queries = List.copyOf(queries);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (queries.isEmpty() || queries.size() > 3 || failedQueries < 0 || failedQueries > queries.size()) {
            throw new IllegalArgumentException("Invalid retrieval trace");
        }
    }
    public record Candidate(long chunkId, long documentId, int rank) { }
}
