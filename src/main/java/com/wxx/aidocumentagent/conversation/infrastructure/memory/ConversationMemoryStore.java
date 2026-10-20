package com.wxx.aidocumentagent.conversation.infrastructure.memory;

import java.util.Optional;

/** 短期上下文缓存端口；任何实现故障均不能影响 MySQL 审计记录。 */
public interface ConversationMemoryStore {

    Optional<ConversationMemorySnapshot> get(long conversationId);

    void put(long conversationId, ConversationMemorySnapshot snapshot);

    void evict(long conversationId);
}
