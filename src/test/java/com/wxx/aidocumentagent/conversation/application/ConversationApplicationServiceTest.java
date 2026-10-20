package com.wxx.aidocumentagent.conversation.application;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentEvent;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentUsage;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationMessagePageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.SendConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryEntry;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.api.dto.RagQueryResponse;
import com.wxx.aidocumentagent.rag.api.dto.RagRetrievalResponse;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryExecution;
import com.wxx.aidocumentagent.rag.domain.RagErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationApplicationServiceTest {

    private static final long CONVERSATION_ID = 42L;
    private static final long KNOWLEDGE_BASE_ID = 7L;

    private KnowledgeBaseRepository knowledgeBaseRepository;
    private ConversationRepository conversationRepository;
    private ConversationMessageRepository messageRepository;
    private ConversationMemoryService memoryService;
    private ConversationTurnPersistenceService turnPersistenceService;
    private RagQueryApplicationService ragQueryApplicationService;
    private AgentRouter agentRouter;
    private List<AgentEvent> agentEvents;
    private ConversationApplicationService service;

    @BeforeEach
    void setUp() {
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(ConversationMessageRepository.class);
        memoryService = mock(ConversationMemoryService.class);
        turnPersistenceService = mock(ConversationTurnPersistenceService.class);
        ragQueryApplicationService = mock(RagQueryApplicationService.class);
        agentRouter = mock(AgentRouter.class);
        agentEvents = new ArrayList<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<RagQueryApplicationService> ragQueryApplicationServiceProvider = mock(ObjectProvider.class);
        when(ragQueryApplicationServiceProvider.getIfAvailable()).thenReturn(ragQueryApplicationService);
        service = new ConversationApplicationService(knowledgeBaseRepository, conversationRepository, messageRepository,
                memoryService, turnPersistenceService, ragQueryApplicationServiceProvider,
                new ConversationMessagePayloadCodec(new JsonMapper()),
                mock(org.springframework.context.ApplicationEventPublisher.class), agentRouter,
                agentEvents::add, new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties());
    }

    @Test
    void 消息列表从会话推导知识库后以二者共同约束查询() {
        Conversation conversation = scopedConversation(2L);
        stubScopedConversation(conversation);
        ConversationMessage user = ConversationMessage.user(CONVERSATION_ID, 1L, "问题");
        ReflectionTestUtils.setField(user, "id", 101L);
        when(messageRepository.findPageByConversationIdAndKnowledgeBaseId(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));

        ConversationMessagePageResponse response = service.listMessages(CONVERSATION_ID, 0, 20);

        assertThat(response.content()).singleElement().satisfies(message -> {
            assertThat(message.conversationId()).isEqualTo(CONVERSATION_ID);
            assertThat(message.role()).isEqualTo(ConversationMessageRole.USER);
            assertThat(message.content()).isEqualTo("问题");
        });
        verify(messageRepository).findPageByConversationIdAndKnowledgeBaseId(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(Pageable.class));
    }

    @Test
    void 模型失败时不会进入写入用户和助手消息的事务() {
        Conversation conversation = scopedConversation(0L);
        stubScopedConversation(conversation);
        when(memoryService.load(conversation)).thenReturn(List.of());
        when(ragQueryApplicationService.queryForConversation(eq(KNOWLEDGE_BASE_ID), any(), any())).thenThrow(
                new BusinessException(RagErrorCode.CHAT_MODEL_UNAVAILABLE));

        assertThatThrownBy(() -> service.sendMessage(CONVERSATION_ID, new SendConversationMessageCommand("问题")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(RagErrorCode.CHAT_MODEL_UNAVAILABLE);

        verify(turnPersistenceService, never()).persistCompletedTurn(any(Long.class), any(Long.class), any(), any());
    }

    @Test
    void 使用有界短期历史调用同一知识库的Rag并再交给原子写入服务() {
        Conversation conversation = scopedConversation(2L);
        stubScopedConversation(conversation);
        when(memoryService.load(conversation)).thenReturn(List.of(
                new ConversationMemoryEntry(1L, ConversationMessageRole.USER, "前一个问题"),
                new ConversationMemoryEntry(2L, ConversationMessageRole.ASSISTANT, "前一个最终回答")));
        RagQueryExecution execution = new RagQueryExecution(new RagQueryResponse("最终回答", List.of(),
                new RagRetrievalResponse(false, 0)), null);
        when(ragQueryApplicationService.queryForConversation(eq(KNOWLEDGE_BASE_ID), any(), any())).thenReturn(execution);
        SendConversationMessageResponse expected = new SendConversationMessageResponse(
                new ConversationMessageResponse(1L, CONVERSATION_ID, 3L, ConversationMessageRole.USER, "当前问题",
                        List.of(), null, null, null),
                new ConversationMessageResponse(2L, CONVERSATION_ID, 4L, ConversationMessageRole.ASSISTANT, "最终回答",
                        List.of(), null, null, null));
        when(turnPersistenceService.persistCompletedTurn(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID), any(), eq(execution)))
                .thenReturn(expected);

        SendConversationMessageResponse actual = service.sendMessage(CONVERSATION_ID,
                new SendConversationMessageCommand("当前问题"));

        assertThat(actual).isSameAs(expected);
        org.mockito.ArgumentCaptor<List<com.wxx.aidocumentagent.rag.application.RagConversationTurn>> history =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(ragQueryApplicationService).queryForConversation(eq(KNOWLEDGE_BASE_ID), any(), history.capture());
        assertThat(history.getValue()).extracting(turn -> turn.role().name()).containsExactly("USER", "ASSISTANT");
        verify(turnPersistenceService).persistCompletedTurn(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID), any(),
                eq(execution));
    }

    @Test
    void 新会话默认检索优先且旧Rag会话仍保留() {
        when(knowledgeBaseRepository.existsById(KNOWLEDGE_BASE_ID)).thenReturn(true);
        when(conversationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
            return conversation;
        });

        var response = service.create(KNOWLEDGE_BASE_ID, new CreateConversationCommand(null, null));

        assertThat(response.agentMode()).isEqualTo(ConversationAgentMode.RETRIEVAL_FIRST);
    }

    @Test
    void 默认Agent消息持久化失败时发失败事件且不发完成事件() {
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话",
                ConversationAgentMode.RETRIEVAL_FIRST);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        stubScopedConversation(conversation);
        when(memoryService.load(conversation)).thenReturn(List.of());
        when(agentRouter.execute(any(AgentRequest.class), any(AgentExecutionContext.class)))
                .thenReturn(new AgentResult("证据不足以回答", List.of(), List.of(), List.of(),
                        AgentResult.Status.COMPLETED, AgentUsage.unknown(), null));
        when(turnPersistenceService.persistCompletedAgentTurn(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(), any(), any())).thenThrow(new DataIntegrityViolationException("private DB detail"));

        assertThatThrownBy(() -> service.sendMessage(CONVERSATION_ID, new SendConversationMessageCommand("问题")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(AgentErrorCode.STORAGE_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("private DB detail");
                });
        assertThat(agentEvents).extracting(AgentEvent::kind).containsExactly(AgentEvent.Kind.FAILED);
        assertThat(agentEvents.getFirst().errorCode()).isEqualTo(AgentErrorCode.STORAGE_FAILED);
    }

    @Test
    void 默认Agent消息提交后才发布完成事件() {
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话",
                ConversationAgentMode.RETRIEVAL_FIRST);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        stubScopedConversation(conversation);
        when(memoryService.load(conversation)).thenReturn(List.of());
        AgentResult result = new AgentResult("证据不足以回答", List.of(), List.of(), List.of(),
                AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);
        when(agentRouter.execute(any(AgentRequest.class), any(AgentExecutionContext.class))).thenReturn(result);
        SendConversationMessageResponse expected = new SendConversationMessageResponse(
                new ConversationMessageResponse(1L, CONVERSATION_ID, 1L, ConversationMessageRole.USER, "问题",
                        List.of(), null, null, null),
                new ConversationMessageResponse(2L, CONVERSATION_ID, 2L, ConversationMessageRole.ASSISTANT,
                        "证据不足以回答", List.of(), null, null, null));
        when(turnPersistenceService.persistCompletedAgentTurn(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(), eq(result), any())).thenReturn(expected);

        assertThat(service.sendMessage(CONVERSATION_ID, new SendConversationMessageCommand("问题"))).isSameAs(expected);
        assertThat(agentEvents).extracting(AgentEvent::kind).containsExactly(AgentEvent.Kind.COMPLETED);
    }

    @Test
    void React会话通过AgentRouter并保留当前知识库边界() {
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话", ConversationAgentMode.REACT);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        stubScopedConversation(conversation);
        when(memoryService.load(conversation)).thenReturn(List.of());
        AgentResult result = new AgentResult("资料不足以回答", List.of(), List.of(), List.of(),
                AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);
        when(agentRouter.execute(any(AgentRequest.class), any(AgentExecutionContext.class))).thenReturn(result);
        SendConversationMessageResponse expected = new SendConversationMessageResponse(
                new ConversationMessageResponse(1L, CONVERSATION_ID, 1L, ConversationMessageRole.USER, "问题",
                        List.of(), null, null, null),
                new ConversationMessageResponse(2L, CONVERSATION_ID, 2L, ConversationMessageRole.ASSISTANT,
                        "资料不足以回答", List.of(), null, null, null));
        when(turnPersistenceService.persistCompletedAgentTurn(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(), eq(result), any())).thenReturn(expected);

        assertThat(service.sendMessage(CONVERSATION_ID, new SendConversationMessageCommand("问题"))).isSameAs(expected);
        org.mockito.ArgumentCaptor<AgentRequest> request = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentRouter).execute(request.capture(), any(AgentExecutionContext.class));
        assertThat(request.getValue().mode()).isEqualTo(AgentMode.REACT);
        assertThat(request.getValue().knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
    }

    @Test
    void Plan会话路由到指定模式且保留知识库边界() {
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话",
                ConversationAgentMode.PLAN_EXECUTE_REFLECT);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        stubScopedConversation(conversation);
        when(memoryService.load(conversation)).thenReturn(List.of());
        AgentResult result = new AgentResult("资料不足以回答", List.of(), List.of(), List.of(),
                AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);
        when(agentRouter.execute(any(AgentRequest.class), any(AgentExecutionContext.class))).thenReturn(result);
        SendConversationMessageResponse expected = new SendConversationMessageResponse(
                new ConversationMessageResponse(1L, CONVERSATION_ID, 1L, ConversationMessageRole.USER, "问题",
                        List.of(), null, null, null),
                new ConversationMessageResponse(2L, CONVERSATION_ID, 2L, ConversationMessageRole.ASSISTANT,
                        "资料不足以回答", List.of(), null, null, null));
        when(turnPersistenceService.persistCompletedAgentTurn(eq(CONVERSATION_ID), eq(KNOWLEDGE_BASE_ID),
                any(), eq(result), any())).thenReturn(expected);

        assertThat(service.sendMessage(CONVERSATION_ID, new SendConversationMessageCommand("问题"))).isSameAs(expected);
        org.mockito.ArgumentCaptor<AgentRequest> request = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentRouter).execute(request.capture(), any(AgentExecutionContext.class));
        assertThat(request.getValue().mode()).isEqualTo(AgentMode.PLAN_EXECUTE_REFLECT);
        assertThat(request.getValue().knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
    }

    private void stubScopedConversation(Conversation conversation) {
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(knowledgeBaseRepository.existsById(KNOWLEDGE_BASE_ID)).thenReturn(true);
        when(conversationRepository.findByIdAndKnowledgeBaseId(CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(conversation));
    }

    private Conversation scopedConversation(long lastMessageSequence) {
        Conversation conversation = Conversation.create(KNOWLEDGE_BASE_ID, "会话", ConversationAgentMode.RAG);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        ReflectionTestUtils.setField(conversation, "lastMessageSequence", lastMessageSequence);
        return conversation;
    }
}
