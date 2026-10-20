package com.wxx.aidocumentagent.conversation.api.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;

/** 可审计的消息视图；仅返回最终用户可见内容和结构化元数据。 */
public record ConversationMessageResponse(
        Long id,
        long conversationId,
        long sequenceNo,
        ConversationMessageRole role,
        String content,
        List<RagCitationResponse> citations,
        String modelName,
        ConversationTokenUsageResponse tokenUsage,
        LocalDateTime createdAt) {

    public ConversationMessageResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
