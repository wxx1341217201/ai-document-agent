package com.wxx.aidocumentagent.conversation.api.dto;

import java.util.Objects;

/** 一次成功发送原子产生的用户消息与最终助手消息。 */
public record SendConversationMessageResponse(
        ConversationMessageResponse userMessage,
        ConversationMessageResponse assistantMessage) {

    public SendConversationMessageResponse {
        Objects.requireNonNull(userMessage, "userMessage不能为空");
        Objects.requireNonNull(assistantMessage, "assistantMessage不能为空");
    }
}
