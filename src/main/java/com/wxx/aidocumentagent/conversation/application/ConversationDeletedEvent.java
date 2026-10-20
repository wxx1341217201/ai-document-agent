package com.wxx.aidocumentagent.conversation.application;

/** 在会话删除提交后清理其短期上下文。 */
public record ConversationDeletedEvent(long conversationId) {
}
