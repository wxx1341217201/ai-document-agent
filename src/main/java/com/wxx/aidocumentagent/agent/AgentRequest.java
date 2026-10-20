package com.wxx.aidocumentagent.agent;

import java.util.Map;

/** 单次执行的不可变输入。mode 为 null 时由路由器选择默认模式。 */
public record AgentRequest(String question, long knowledgeBaseId, Long conversationId, AgentMode mode,
                           Map<String, String> parameters) {

    public AgentRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question不能为空");
        }
        question = question.strip();
        if (knowledgeBaseId <= 0L) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        if (conversationId != null && conversationId <= 0L) {
            throw new IllegalArgumentException("conversationId必须大于0");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
