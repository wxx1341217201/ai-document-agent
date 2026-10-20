package com.wxx.aidocumentagent.conversation.api.dto;

/** 模型服务商明确返回时才有值；null 表示未知，绝不伪造为 0。 */
public record ConversationTokenUsageResponse(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
