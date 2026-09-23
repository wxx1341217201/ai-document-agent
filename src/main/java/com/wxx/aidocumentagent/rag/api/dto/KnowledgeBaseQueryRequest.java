package com.wxx.aidocumentagent.rag.api.dto;

import com.wxx.aidocumentagent.rag.RagProperties;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 知识库问答的 HTTP 请求。流式协议尚未在 M11 定义，因此明确拒绝 stream=true。 */
public record KnowledgeBaseQueryRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2_000, message = "问题不能超过2000个字符")
        String question,
        @Min(value = 1, message = "topK不能小于1")
        @Max(value = RagProperties.MAX_TOP_K, message = "topK不能超过20")
        Integer topK,
        Boolean rerank,
        @AssertFalse(message = "当前接口暂不支持流式输出")
        Boolean stream) {

    public KnowledgeBaseQueryRequest {
        question = question == null ? null : question.strip();
    }

    public boolean rerankEnabled() {
        return Boolean.TRUE.equals(rerank);
    }
}
