package com.wxx.aidocumentagent.conversation.application;

/** 在事务提交后刷新缓存的事件；未提交的会话消息绝不进入 Redis。 */
public record ConversationTurnPersistedEvent(long conversationId, long knowledgeBaseId, long lastMessageSequence) {
}
