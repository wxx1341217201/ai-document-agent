package com.wxx.aidocumentagent.conversation.api.dto;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;

/** 会话 REST 响应，不暴露内部并发控制字段。 */
public record ConversationResponse(
        Long id,
        long knowledgeBaseId,
        String title,
        ConversationAgentMode agentMode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
