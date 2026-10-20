package com.wxx.aidocumentagent.rag.application;

import java.util.List;
import java.util.Objects;

/** 已在总预算内完成去冗余并固定引用 ID 的模型上下文。 */
record RagContext(String userPrompt, List<RagCitation> citations, int candidateCount,
                  List<RagEvidenceChunk> evidence) {

    RagContext {
        userPrompt = Objects.requireNonNull(userPrompt, "userPrompt不能为空");
        citations = citations == null ? List.of() : List.copyOf(citations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount不能为负数");
        }
    }

    RagContext(String userPrompt, List<RagCitation> citations, int candidateCount) {
        this(userPrompt, citations, candidateCount, List.of());
    }

    boolean hasEvidence() {
        return !citations.isEmpty();
    }
}
