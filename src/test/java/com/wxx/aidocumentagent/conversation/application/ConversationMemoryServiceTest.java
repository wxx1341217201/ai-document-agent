package com.wxx.aidocumentagent.conversation.application;

import java.util.List;
import java.util.Optional;

import com.wxx.aidocumentagent.conversation.ConversationMemoryProperties;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryEntry;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemorySnapshot;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryStore;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    private static final long CONVERSATION_ID = 42L;
    private static final long KNOWLEDGE_BASE_ID = 7L;

    private ConversationMessageRepository messageRepository;
    private ConversationMemoryStore memoryStore;
    private ConversationMemoryProperties properties;
    private ConversationMemoryService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ConversationMessageRepository.class);
        memoryStore = mock(ConversationMemoryStore.class);
        properties = new ConversationMemoryProperties();
        properties.setMaxMemoryMessages(4);
        properties.setMaxMemoryTokens(100);
        service = new ConversationMemoryService(messageRepository, memoryStore, properties, codePointEstimator());
    }

    @Test
    void 缓存命中且消息水位一致时不访问MySql() {
        Conversation conversation = conversation(4L);
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot(4L, List.of(
                new ConversationMemoryEntry(1L, ConversationMessageRole.USER, "第一问"),
                new ConversationMemoryEntry(2L, ConversationMessageRole.ASSISTANT, "第一答")));
        when(memoryStore.get(CONVERSATION_ID)).thenReturn(Optional.of(snapshot));

        List<ConversationMemoryEntry> result = service.load(conversation);

        assertThat(result).isEqualTo(snapshot.messages());
        verify(messageRepository, never()).findRecentByConversationIdAndKnowledgeBaseId(any(Long.class), any(Long.class),
                any(Pageable.class));
    }

    @Test
    void 缓存未命中时按知识库边界从MySql重建并写回缓存() {
        Conversation conversation = conversation(4L);
        when(memoryStore.get(CONVERSATION_ID)).thenReturn(Optional.empty());
        ConversationMessage assistant = ConversationMessage.assistant(CONVERSATION_ID, 4L, "第二答", null, "model", null);
        ConversationMessage user = ConversationMessage.user(CONVERSATION_ID, 3L, "第二问");
        when(messageRepository.findRecentByConversationIdAndKnowledgeBaseId(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(Pageable.class))).thenReturn(List.of(assistant, user));

        List<ConversationMemoryEntry> result = service.load(conversation);

        assertThat(result).extracting(ConversationMemoryEntry::sequenceNo).containsExactly(3L, 4L);
        verify(messageRepository).findRecentByConversationIdAndKnowledgeBaseId(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(Pageable.class));
        org.mockito.ArgumentCaptor<ConversationMemorySnapshot> snapshot = org.mockito.ArgumentCaptor.forClass(
                ConversationMemorySnapshot.class);
        verify(memoryStore).put(eq(CONVERSATION_ID), snapshot.capture());
        assertThat(snapshot.getValue().lastMessageSequence()).isEqualTo(4L);
        assertThat(snapshot.getValue().messages()).isEqualTo(result);
    }

    @Test
    void 上下文按最近消息数和总token预算裁剪() {
        properties.setMaxMemoryMessages(2);
        properties.setMaxMemoryTokens(5);
        ConversationMemoryTrimmer trimmer = new ConversationMemoryTrimmer(properties, codePointEstimator());

        List<ConversationMemoryEntry> result = trimmer.trim(List.of(
                new ConversationMemoryEntry(1L, ConversationMessageRole.USER, "旧消息"),
                new ConversationMemoryEntry(2L, ConversationMessageRole.ASSISTANT, "1234"),
                new ConversationMemoryEntry(3L, ConversationMessageRole.USER, "ABCDE")));

        assertThat(result).extracting(ConversationMemoryEntry::sequenceNo).containsExactly(3L);
        assertThat(result.getFirst().content()).isEqualTo("ABCDE");
        assertThat(result.stream().mapToInt(message -> message.content().length()).sum()).isLessThanOrEqualTo(5);
    }

    @Test
    void 陈旧缓存水位会回源而不是使用缺失的新消息() {
        Conversation conversation = conversation(4L);
        when(memoryStore.get(CONVERSATION_ID)).thenReturn(Optional.of(new ConversationMemorySnapshot(2L, List.of(
                new ConversationMemoryEntry(1L, ConversationMessageRole.USER, "旧消息")))));
        when(messageRepository.findRecentByConversationIdAndKnowledgeBaseId(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(Pageable.class))).thenReturn(List.of(
                ConversationMessage.assistant(CONVERSATION_ID, 4L, "新回答", null, null, null),
                ConversationMessage.user(CONVERSATION_ID, 3L, "新问题")));

        List<ConversationMemoryEntry> result = service.load(conversation);

        assertThat(result).extracting(ConversationMemoryEntry::sequenceNo).containsExactly(3L, 4L);
        verify(messageRepository).findRecentByConversationIdAndKnowledgeBaseId(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(Pageable.class));
    }

    private Conversation conversation(long lastMessageSequence) {
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "测试会话", ConversationAgentMode.RAG);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        ReflectionTestUtils.setField(conversation, "lastMessageSequence", lastMessageSequence);
        return conversation;
    }

    private TokenCountEstimator codePointEstimator() {
        return new TokenCountEstimator() {
            @Override
            public int estimate(String text) {
                return text == null ? 0 : text.codePointCount(0, text.length());
            }

            @Override
            public int estimate(MediaContent content) {
                return 0;
            }

            @Override
            public int estimate(Iterable<MediaContent> contents) {
                return 0;
            }
        };
    }
}
