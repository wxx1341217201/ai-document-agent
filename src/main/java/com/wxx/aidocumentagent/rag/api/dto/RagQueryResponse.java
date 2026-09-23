package com.wxx.aidocumentagent.rag.api.dto;

import java.util.List;

/** M11 非流式问答响应。 */
public record RagQueryResponse(String answer, List<RagCitationResponse> citations, RagRetrievalResponse retrieval) {

    public RagQueryResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
