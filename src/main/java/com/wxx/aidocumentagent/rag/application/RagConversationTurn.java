package com.wxx.aidocumentagent.rag.application;

import java.util.Objects;

/** 传给 RAG 的有限、用户可见对话历史；不包含工具原始输出或隐藏推理。 */
public record RagConversationTurn(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public RagConversationTurn {
        role = Objects.requireNonNull(role, "role不能为空");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content不能为空");
        }
    }
}
