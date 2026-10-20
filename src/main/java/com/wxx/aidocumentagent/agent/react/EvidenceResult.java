package com.wxx.aidocumentagent.agent.react;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record EvidenceResult(List<EvidenceChunk> chunks, Map<String, String> retrievalSummary, String selectionQuery) {
    public EvidenceResult(List<EvidenceChunk> chunks) { this(chunks, Map.of(), null); }
    public EvidenceResult(List<EvidenceChunk> chunks, Map<String, String> summary) { this(chunks, summary, null); }
    public EvidenceResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        retrievalSummary = retrievalSummary == null ? Map.of() : Map.copyOf(retrievalSummary);
        if (selectionQuery != null && selectionQuery.length() > 2_000) throw new IllegalArgumentException("Invalid selection query");
        if (!Set.of("queryCount", "rerankApplied", "retrievalDegraded").containsAll(retrievalSummary.keySet())) {
            throw new IllegalArgumentException("Unknown retrieval summary field");
        }
        for (var entry : retrievalSummary.entrySet()) {
            if (!(entry.getKey().equals("queryCount") ? entry.getValue().matches("[1-3]")
                    : entry.getValue().matches("true|false"))) throw new IllegalArgumentException("Invalid retrieval summary");
        }
    }
}
