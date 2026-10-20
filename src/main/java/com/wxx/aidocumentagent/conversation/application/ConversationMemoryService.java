package com.wxx.aidocumentagent.conversation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.conversation.ConversationMemoryProperties;
import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryEntry;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemorySnapshot;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryStore;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** 缓存命中且水位一致时直接使用 Redis；缺失、损坏或陈旧时按 knowledgeBaseId 从 MySQL 重建。 */
@Service
@ConditionalOnProperty(prefix = "app.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationMemoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ConversationMessageRepository messageRepository;
    private final ConversationMemoryStore memoryStore;
    private final ConversationMemoryTrimmer trimmer;
    private final ConversationMemoryProperties properties;

    public ConversationMemoryService(ConversationMessageRepository messageRepository,
                                     ConversationMemoryStore memoryStore,
                                     ConversationMemoryProperties properties,
                                     @Qualifier("conversationTokenCountEstimator") TokenCountEstimator tokenCountEstimator) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "conversationMessageRepository不能为空");
        this.memoryStore = Objects.requireNonNull(memoryStore, "conversationMemoryStore不能为空");
        this.properties = Objects.requireNonNull(properties, "conversationMemoryProperties不能为空");
        this.trimmer = new ConversationMemoryTrimmer(properties, tokenCountEstimator);
    }

    public List<ConversationMemoryEntry> load(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation不能为空");
        long conversationId = requiredId(conversation);
        try {
            var cached = memoryStore.get(conversationId);
            if (cached.isPresent() && cached.get().lastMessageSequence() == conversation.getLastMessageSequence()) {
                return trimmer.trim(cached.get().messages());
            }
        }
        catch (RuntimeException exception) {
            LOGGER.warn("读取会话短期上下文缓存异常，将从数据库重建: conversationId={}", conversationId);
        }
        return rebuild(conversationId, conversation.getKnowledgeBaseId(), conversation.getLastMessageSequence());
    }

    /** 事务提交后调用；并发下旧快照也会由 sequence 水位检测为陈旧，绝不会作为上下文使用。 */
    public void refreshAfterCommit(long conversationId, long knowledgeBaseId, long lastMessageSequence) {
        try {
            rebuild(conversationId, knowledgeBaseId, lastMessageSequence);
        }
        catch (RuntimeException exception) {
            LOGGER.warn("会话提交后重建短期上下文失败，将在下次请求回源: conversationId={}", conversationId);
        }
    }

    public void evictAfterCommit(long conversationId) {
        try {
            memoryStore.evict(conversationId);
        }
        catch (RuntimeException exception) {
            LOGGER.warn("会话删除后清理短期上下文失败，缓存会在TTL后失效: conversationId={}", conversationId);
        }
    }

    private List<ConversationMemoryEntry> rebuild(long conversationId, long knowledgeBaseId, long lastMessageSequence) {
        List<ConversationMessage> newestFirst = messageRepository.findRecentByConversationIdAndKnowledgeBaseId(
                conversationId, knowledgeBaseId, PageRequest.of(0, properties.getMaxMemoryMessages()));
        List<ConversationMemoryEntry> chronological = new ArrayList<>();
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            ConversationMessage message = newestFirst.get(index);
            if (message == null || (message.getRole() != ConversationMessageRole.USER
                    && message.getRole() != ConversationMessageRole.ASSISTANT)) {
                continue;
            }
            chronological.add(new ConversationMemoryEntry(message.getSequenceNo(), message.getRole(),
                    message.getContent()));
        }
        List<ConversationMemoryEntry> bounded = trimmer.trim(chronological);
        try {
            memoryStore.put(conversationId, new ConversationMemorySnapshot(lastMessageSequence, bounded));
        }
        catch (RuntimeException exception) {
            LOGGER.warn("写入会话短期上下文缓存异常，将在下次请求回源: conversationId={}", conversationId);
        }
        return bounded;
    }

    private long requiredId(Conversation conversation) {
        if (conversation.getId() == null || conversation.getId() <= 0L) {
            throw new IllegalArgumentException("conversation必须已经持久化");
        }
        return conversation.getId();
    }
}
