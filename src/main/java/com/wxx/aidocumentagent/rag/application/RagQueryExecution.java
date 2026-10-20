package com.wxx.aidocumentagent.rag.application;

import java.util.Objects;
import java.util.List;

import com.wxx.aidocumentagent.rag.api.dto.RagQueryResponse;

/**
 * RAG 问答的内部执行结果。completion 仅含最终模型输出的元数据；未调用模型（例如无证据）时为 null。
 */
public record RagQueryExecution(RagQueryResponse response, RagChatCompletion completion,
                                List<RagEvidenceChunk> evidence, String promptSha256, RagRetrievalTrace retrievalTrace) {

    public RagQueryExecution {
        Objects.requireNonNull(response, "response不能为空");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public RagQueryExecution(RagQueryResponse response, RagChatCompletion completion) {
        this(response, completion, List.of(), null, null);
    }

    public RagQueryExecution(RagQueryResponse response, RagChatCompletion completion,
                             List<RagEvidenceChunk> evidence, String promptSha256) {
        this(response, completion, evidence, promptSha256, null);
    }
}
