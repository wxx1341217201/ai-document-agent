package com.wxx.aidocumentagent.conversation.application;

import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 只在数据库提交后更新或删除 Redis，避免回滚事务污染短期上下文。 */
@Component
@ConditionalOnProperty(prefix = "app.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationMemoryCacheListener {

    private final ConversationMemoryService memoryService;

    public ConversationMemoryCacheListener(ConversationMemoryService memoryService) {
        this.memoryService = Objects.requireNonNull(memoryService, "conversationMemoryService不能为空");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void refresh(ConversationTurnPersistedEvent event) {
        memoryService.refreshAfterCommit(event.conversationId(), event.knowledgeBaseId(), event.lastMessageSequence());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void evict(ConversationDeletedEvent event) {
        memoryService.evictAfterCommit(event.conversationId());
    }
}
