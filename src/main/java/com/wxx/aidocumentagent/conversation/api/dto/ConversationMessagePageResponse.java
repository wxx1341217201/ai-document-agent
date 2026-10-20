package com.wxx.aidocumentagent.conversation.api.dto;

import java.util.List;

/** 会话消息的稳定分页响应；顺序由 sequenceNo、id 固化。 */
public record ConversationMessagePageResponse(
        List<ConversationMessageResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
