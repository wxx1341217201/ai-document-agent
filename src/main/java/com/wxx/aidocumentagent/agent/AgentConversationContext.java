package com.wxx.aidocumentagent.agent;

import java.util.List;

/** 已按知识库边界加载并裁剪的会话快照；调用方负责查询时显式传入 knowledgeBaseId。 */
public record AgentConversationContext(long knowledgeBaseId, Long conversationId, List<AgentMemoryMessage> messages) {

    public AgentConversationContext {
        if (knowledgeBaseId <= 0L) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        if (conversationId != null && conversationId <= 0L) {
            throw new IllegalArgumentException("conversationId必须大于0");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (conversationId == null && !messages.isEmpty()) {
            throw new IllegalArgumentException("无会话时不能附带历史消息");
        }
    }

    public static AgentConversationContext emptyFor(AgentRequest request) {
        return new AgentConversationContext(request.knowledgeBaseId(), request.conversationId(), List.of());
    }
}
