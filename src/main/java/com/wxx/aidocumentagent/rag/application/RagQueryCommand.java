package com.wxx.aidocumentagent.rag.application;

/** 应用层输入，避免 Controller 将 HTTP DTO 传入业务规则。 */
public record RagQueryCommand(String question, Integer topK, boolean rerank) {

    public RagQueryCommand {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question不能为空");
        }
    }
}
