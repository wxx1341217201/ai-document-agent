package com.wxx.aidocumentagent.conversation.api.dto;

import java.util.List;

/** 显式分页协议，不泄露 Spring Data Page 实现。 */
public record ConversationPageResponse(
        List<ConversationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
