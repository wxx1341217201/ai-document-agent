package com.wxx.aidocumentagent.conversation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;

import com.wxx.aidocumentagent.agent.AgentCancellationToken;
import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;
import com.wxx.aidocumentagent.agent.AgentUsage;
import com.wxx.aidocumentagent.conversation.api.dto.SendConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.rag.api.dto.RagQueryResponse;
import com.wxx.aidocumentagent.rag.api.dto.RagRetrievalResponse;
import com.wxx.aidocumentagent.rag.application.RagChatCompletion;
import com.wxx.aidocumentagent.rag.application.RagQueryExecution;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTurnPersistenceServiceTest {

    private static final long CONVERSATION_ID = 42L;
    private static final long KNOWLEDGE_BASE_ID = 7L;

    @Test
    void 并发完成的消息分配连续且不重复的稳定序号() throws Exception {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话", ConversationAgentMode.RAG);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        when(conversationRepository.findLockedByIdAndKnowledgeBaseId(CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(conversation));
        AtomicLong messageId = new AtomicLong(100L);
        when(messageRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ConversationMessage> messages = new ArrayList<>((List<ConversationMessage>) invocation.getArgument(0));
            for (ConversationMessage message : messages) {
                ReflectionTestUtils.setField(message, "id", messageId.incrementAndGet());
            }
            return messages;
        });
        ConversationTurnPersistenceService service = new ConversationTurnPersistenceService(conversationRepository,
                messageRepository, new ConversationMessagePayloadCodec(new JsonMapper()), eventPublisher);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Callable<SendConversationMessageResponse> first = () -> service.persistCompletedTurn(CONVERSATION_ID,
                    KNOWLEDGE_BASE_ID, new SendConversationMessageCommand("第一个问题"), execution("第一个回答"));
            Callable<SendConversationMessageResponse> second = () -> service.persistCompletedTurn(CONVERSATION_ID,
                    KNOWLEDGE_BASE_ID, new SendConversationMessageCommand("第二个问题"), execution("第二个回答"));
            List<Future<SendConversationMessageResponse>> results = executor.invokeAll(List.of(first, second));

            List<Long> sequences = results.stream().flatMap(result -> {
                try {
                    SendConversationMessageResponse response = result.get();
                    return java.util.stream.Stream.of(response.userMessage().sequenceNo(),
                            response.assistantMessage().sequenceNo());
                }
                catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).sorted().toList();
            assertThat(sequences).containsExactly(1L, 2L, 3L, 4L);
            assertThat(conversation.getLastMessageSequence()).isEqualTo(4L);
        }
        finally {
            executor.close();
        }
    }

    @Test
    void 消息持久化失败时不会发布缓存刷新事件或返回助手半条消息() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话", ConversationAgentMode.RAG);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        when(conversationRepository.findLockedByIdAndKnowledgeBaseId(CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.saveAllAndFlush(any())).thenThrow(new DataIntegrityViolationException("simulated"));
        ConversationTurnPersistenceService service = new ConversationTurnPersistenceService(conversationRepository,
                messageRepository, new ConversationMessagePayloadCodec(new JsonMapper()), eventPublisher);

        assertThatThrownBy(() -> service.persistCompletedTurn(CONVERSATION_ID, KNOWLEDGE_BASE_ID,
                new SendConversationMessageCommand("问题"), execution("回答")))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 检索优先结果写入失败时原子保存方法抛错且不发布提交事件() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话",
                ConversationAgentMode.RETRIEVAL_FIRST);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        when(conversationRepository.findLockedByIdAndKnowledgeBaseId(CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.saveAllAndFlush(any())).thenThrow(new DataIntegrityViolationException("simulated"));
        ConversationTurnPersistenceService service = new ConversationTurnPersistenceService(conversationRepository,
                messageRepository, new ConversationMessagePayloadCodec(new JsonMapper()), eventPublisher);
        AgentRequest request = new AgentRequest("问题", KNOWLEDGE_BASE_ID, CONVERSATION_ID,
                AgentMode.RETRIEVAL_FIRST, java.util.Map.of());
        AgentExecutionContext context = AgentExecutionContext.forConversationTurn(request, "trace-7",
                Instant.now().plusSeconds(5), new AgentConversationContext(KNOWLEDGE_BASE_ID, CONVERSATION_ID,
                        List.of()), event -> { }, new AgentCancellationToken());
        AgentResult result = new AgentResult("证据不足以回答", List.of(), List.of(), List.of(),
                AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);

        assertThatThrownBy(() -> service.persistCompletedAgentTurn(CONVERSATION_ID, KNOWLEDGE_BASE_ID,
                new SendConversationMessageCommand("问题"), result, context))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 检索优先只保存最终回答已核验引用和模型元数据() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话",
                ConversationAgentMode.RETRIEVAL_FIRST);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        when(conversationRepository.findLockedByIdAndKnowledgeBaseId(CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ConversationMessage> messages = invocation.getArgument(0);
            ReflectionTestUtils.setField(messages.get(0), "id", 101L);
            ReflectionTestUtils.setField(messages.get(1), "id", 102L);
            return messages;
        });
        ConversationTurnPersistenceService service = new ConversationTurnPersistenceService(conversationRepository,
                messageRepository, new ConversationMessagePayloadCodec(new JsonMapper()), eventPublisher);
        AgentRequest request = new AgentRequest("问题", KNOWLEDGE_BASE_ID, CONVERSATION_ID,
                AgentMode.RETRIEVAL_FIRST, java.util.Map.of());
        AgentExecutionContext context = AgentExecutionContext.forConversationTurn(request, "trace-7",
                Instant.now().plusSeconds(5), new AgentConversationContext(KNOWLEDGE_BASE_ID, CONVERSATION_ID,
                        List.of()), event -> { }, new AgentCancellationToken());
        AgentResult result = new AgentResult("回答。[C1]", List.of(new AgentCitation(KNOWLEDGE_BASE_ID, "C1",
                11L, "document.pdf", 21L, 2, 2, "证据")),
                List.of(new AgentStepSummary("tool1", AgentStepSummary.Kind.TOOL, "searchKnowledgeBase",
                        AgentStepSummary.Status.COMPLETED, null)),
                List.of(new AgentToolCallSummary("searchKnowledgeBase", AgentStepSummary.Status.COMPLETED,
                        12L, null, java.util.Map.of("queryLength", "7"))),
                AgentResult.Status.COMPLETED, new AgentUsage(5, 3, 8, 1, 1), null, "test-model");

        SendConversationMessageResponse saved = service.persistCompletedAgentTurn(CONVERSATION_ID, KNOWLEDGE_BASE_ID,
                new SendConversationMessageCommand("问题"), result, context);

        assertThat(saved.userMessage().content()).isEqualTo("问题");
        assertThat(saved.assistantMessage().content()).isEqualTo("回答。[C1]");
        assertThat(saved.assistantMessage().citations()).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isEqualTo(11L);
            assertThat(citation.chunkId()).isEqualTo(21L);
        });
        assertThat(saved.assistantMessage().modelName()).isEqualTo("test-model");
        assertThat(saved.assistantMessage().tokenUsage().totalTokens()).isEqualTo(8);
        org.mockito.ArgumentCaptor<List<ConversationMessage>> messages = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAllAndFlush(messages.capture());
        ConversationMessage persistedAssistant = messages.getValue().get(1);
        assertThat(persistedAssistant.getAgentStepsJson().toString()).contains("searchKnowledgeBase");
        assertThat(persistedAssistant.getToolCallsJson().toString()).contains("queryLength", "elapsedMillis")
                .doesNotContain("证据");
        verify(eventPublisher).publishEvent(any(ConversationTurnPersistedEvent.class));
    }

    private RagQueryExecution execution(String answer) {
        return new RagQueryExecution(new RagQueryResponse(answer, List.of(), new RagRetrievalResponse(false, 1)),
                new RagChatCompletion("模型最终回答", "test-model", 5, 3, 8));
    }
}
