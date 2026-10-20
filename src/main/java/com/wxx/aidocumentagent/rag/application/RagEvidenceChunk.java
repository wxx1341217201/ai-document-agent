package com.wxx.aidocumentagent.rag.application;

import java.util.Objects;

import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;

/** 内部评测证据：保存实际送入模型的截断后正文，绝不保存模型推理或整个提示词。 */
public record RagEvidenceChunk(long knowledgeBaseId, RagCitationResponse citation,
                               String contextText, String sourceContentSha256) {

    public RagEvidenceChunk {
        Objects.requireNonNull(citation, "citation不能为空");
        if (knowledgeBaseId <= 0 || citation.chunkId() <= 0 || citation.documentId() <= 0
                || contextText == null || contextText.isBlank()
                || sourceContentSha256 == null || !sourceContentSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("证据必须具有知识库、真实chunk、正文和源内容摘要");
        }
    }
}
