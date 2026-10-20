package com.wxx.aidocumentagent.conversation.infrastructure.memory;

import java.util.Optional;

/** Redis 自动配置被关闭时的安全降级实现；调用方会从 MySQL 重建短期上下文。 */
public final class NoOpConversationMemoryStore implements ConversationMemoryStore {

    @Override
    public Optional<ConversationMemorySnapshot> get(long conversationId) {
        return Optional.empty();
    }

    @Override
    public void put(long conversationId, ConversationMemorySnapshot snapshot) {
        // Intentionally no-op.
    }

    @Override
    public void evict(long conversationId) {
        // Intentionally no-op.
    }
}
