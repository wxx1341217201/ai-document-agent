package com.wxx.aidocumentagent.conversation.application;

import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.wxx.aidocumentagent.agent.AgentCancellationToken;
import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentEvent;
import com.wxx.aidocumentagent.agent.AgentEventPublisher;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.api.CommonErrorCode;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationMessagePageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationPageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationResponse;
import com.wxx.aidocumentagent.conversation.api.dto.SendConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryEntry;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.application.RagConversationTurn;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryExecution;
import com.wxx.aidocumentagent.rag.application.RagQueryCommand;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M12 会话应用服务。所有消息读取与写入都携带从会话解析出的 knowledgeBaseId，防止跨知识库串读。
 */
@Service
@ConditionalOnProperty(prefix = "app.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationApplicationService {

    private static final String DEFAULT_TITLE = "新会话";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationMemoryService memoryService;
    private final ConversationTurnPersistenceService turnPersistenceService;
    private final ObjectProvider<RagQueryApplicationService> ragQueryApplicationServiceProvider;
    private final ConversationMessagePayloadCodec payloadCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentRouter agentRouter;
    private final AgentEventPublisher agentEventPublisher;
    private final RetrievalFirstProperties retrievalFirstProperties;
    private final ReActProperties reactProperties;
    private final PlanProperties planProperties;

    public ConversationApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                          ConversationRepository conversationRepository,
                                          ConversationMessageRepository messageRepository,
                                          ConversationMemoryService memoryService,
                                          ConversationTurnPersistenceService turnPersistenceService,
                                          ObjectProvider<RagQueryApplicationService> ragQueryApplicationServiceProvider,
                                          ConversationMessagePayloadCodec payloadCodec,
                                          ApplicationEventPublisher eventPublisher,
                                          AgentRouter agentRouter,
                                          AgentEventPublisher agentEventPublisher,
                                          RetrievalFirstProperties retrievalFirstProperties,
                                          ReActProperties reactProperties,
                                          PlanProperties planProperties) {
        this.knowledgeBaseRepository = Objects.requireNonNull(knowledgeBaseRepository, "knowledgeBaseRepository不能为空");
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository不能为空");
        this.messageRepository = Objects.requireNonNull(messageRepository, "conversationMessageRepository不能为空");
        this.memoryService = Objects.requireNonNull(memoryService, "conversationMemoryService不能为空");
        this.turnPersistenceService = Objects.requireNonNull(turnPersistenceService,
                "conversationTurnPersistenceService不能为空");
        this.ragQueryApplicationServiceProvider = Objects.requireNonNull(ragQueryApplicationServiceProvider,
                "ragQueryApplicationServiceProvider不能为空");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "conversationMessagePayloadCodec不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher不能为空");
        this.agentRouter = Objects.requireNonNull(agentRouter, "agentRouter不能为空");
        this.agentEventPublisher = Objects.requireNonNull(agentEventPublisher, "agentEventPublisher不能为空");
        this.retrievalFirstProperties = Objects.requireNonNull(retrievalFirstProperties,
                "retrievalFirstProperties不能为空");
        this.reactProperties = Objects.requireNonNull(reactProperties, "reactProperties不能为空");
        this.planProperties = Objects.requireNonNull(planProperties, "planProperties不能为空");
    }

    @Transactional
    public ConversationResponse create(long knowledgeBaseId, CreateConversationCommand command) {
        requireKnowledgeBase(knowledgeBaseId);
        if (command == null) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        Conversation conversation = Conversation.create(knowledgeBaseId, normalizeTitle(command.title()),
                command.agentMode() == null ? ConversationAgentMode.RETRIEVAL_FIRST : command.agentMode());
        return toResponse(conversationRepository.saveAndFlush(conversation));
    }

    @Transactional(readOnly = true)
    public ConversationPageResponse list(long knowledgeBaseId, int page, int size) {
        requireKnowledgeBase(knowledgeBaseId);
        Page<Conversation> result = conversationRepository.findByKnowledgeBaseId(knowledgeBaseId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        return new ConversationPageResponse(result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ConversationMessagePageResponse listMessages(long conversationId, int page, int size) {
        Conversation conversation = requireScopedConversation(conversationId);
        Page<ConversationMessage> result = messageRepository.findPageByConversationIdAndKnowledgeBaseId(conversationId,
                conversation.getKnowledgeBaseId(), PageRequest.of(page, size));
        return new ConversationMessagePageResponse(result.getContent().stream().map(payloadCodec::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * 模型调用发生在消息写事务之外。只有它生成可返回的最终结果后才调用持久化服务，避免失败留下半条助手消息。
     */
    public SendConversationMessageResponse sendMessage(long conversationId, SendConversationMessageCommand command) {
        Instant requestedAt = Instant.now();
        if (command == null) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        Conversation conversation = requireScopedConversation(conversationId);
        List<ConversationMemoryEntry> history = memoryService.load(conversation);
        if (conversation.getAgentMode() == ConversationAgentMode.RETRIEVAL_FIRST
                || conversation.getAgentMode() == ConversationAgentMode.REACT
                || conversation.getAgentMode() == ConversationAgentMode.PLAN_EXECUTE_REFLECT) {
            return sendAgent(conversation, command, history, requestedAt);
        }
        RagQueryApplicationService ragQueryApplicationService = ragQueryApplicationServiceProvider.getIfAvailable();
        if (ragQueryApplicationService == null) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_ANSWERING_UNAVAILABLE);
        }
        RagQueryExecution execution = ragQueryApplicationService.queryForConversation(conversation.getKnowledgeBaseId(),
                new RagQueryCommand(command.content(), null, false), toRagHistory(history));
        return turnPersistenceService.persistCompletedTurn(conversationId, conversation.getKnowledgeBaseId(), command,
                execution);
    }

    private SendConversationMessageResponse sendAgent(Conversation conversation,
                                                                SendConversationMessageCommand command,
                                                                List<ConversationMemoryEntry> history,
                                                                Instant requestedAt) {
        long knowledgeBaseId = conversation.getKnowledgeBaseId();
        long conversationId = conversation.getId();
        AgentMode mode = switch (conversation.getAgentMode()) {
            case REACT -> AgentMode.REACT;
            case PLAN_EXECUTE_REFLECT -> AgentMode.PLAN_EXECUTE_REFLECT;
            default -> AgentMode.RETRIEVAL_FIRST;
        };
        AgentRequest request = new AgentRequest(command.content(), knowledgeBaseId, conversationId, mode, Map.of());
        List<AgentMemoryMessage> memory = history.stream()
                .map(message -> new AgentMemoryMessage(message.sequenceNo(),
                        message.role() == com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole.USER
                                ? AgentMemoryMessage.Role.USER : AgentMemoryMessage.Role.ASSISTANT,
                        message.content()))
                .toList();
        String traceId = TraceIdFilter.currentTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        AgentExecutionContext context = AgentExecutionContext.forConversationTurn(request, traceId,
                requestedAt.plus(switch (mode) {
                    case REACT -> reactProperties.getDeadline();
                    case PLAN_EXECUTE_REFLECT -> planProperties.getDeadline();
                    default -> retrievalFirstProperties.getDeadline();
                }),
                new AgentConversationContext(knowledgeBaseId, conversationId, memory), agentEventPublisher,
                new AgentCancellationToken());
        AgentResult result = agentRouter.execute(request, context);
        if (result.status() != AgentResult.Status.COMPLETED) {
            throw new BusinessException(result.failure().code());
        }
        SendConversationMessageResponse saved;
        try {
            saved = turnPersistenceService.persistCompletedAgentTurn(conversationId,
                    knowledgeBaseId, command, result, context);
        }
        catch (BusinessException exception) {
            context.publish(mode, AgentEvent.Kind.FAILED, null,
                    exception.getErrorCode() instanceof AgentErrorCode agentCode
                            ? agentCode : AgentErrorCode.STORAGE_FAILED);
            throw exception;
        }
        catch (RuntimeException exception) {
            context.publish(mode, AgentEvent.Kind.FAILED, null, AgentErrorCode.STORAGE_FAILED);
            throw new BusinessException(AgentErrorCode.STORAGE_FAILED);
        }
        context.publish(mode, AgentEvent.Kind.COMPLETED, null, null);
        return saved;
    }

    @Transactional
    public void delete(long conversationId) {
        Conversation conversation = requireScopedConversation(conversationId);
        Conversation locked = conversationRepository.findLockedByIdAndKnowledgeBaseId(conversationId,
                conversation.getKnowledgeBaseId()).orElseThrow(() -> new BusinessException(
                        ConversationErrorCode.CONVERSATION_NOT_FOUND));
        conversationRepository.delete(locked);
        conversationRepository.flush();
        eventPublisher.publishEvent(new ConversationDeletedEvent(conversationId));
    }

    private Conversation requireScopedConversation(long conversationId) {
        if (conversationId <= 0L) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        Conversation candidate = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
        long knowledgeBaseId = candidate.getKnowledgeBaseId();
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND);
        }
        // 再以 conversationId + knowledgeBaseId 取回，确保后续任何跨表读取都受知识库边界约束。
        return conversationRepository.findByIdAndKnowledgeBaseId(conversationId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
    }

    private void requireKnowledgeBase(long knowledgeBaseId) {
        if (knowledgeBaseId <= 0L) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    private List<RagConversationTurn> toRagHistory(List<ConversationMemoryEntry> history) {
        return history.stream().map(message -> new RagConversationTurn(
                message.role() == com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole.USER
                        ? RagConversationTurn.Role.USER : RagConversationTurn.Role.ASSISTANT,
                message.content())).toList();
    }

    private String normalizeTitle(String title) {
        return title == null || title.isBlank() ? DEFAULT_TITLE : title.strip();
    }

    private ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(conversation.getId(), conversation.getKnowledgeBaseId(), conversation.getTitle(),
                conversation.getAgentMode(), conversation.getCreatedAt(), conversation.getUpdatedAt());
    }
}
