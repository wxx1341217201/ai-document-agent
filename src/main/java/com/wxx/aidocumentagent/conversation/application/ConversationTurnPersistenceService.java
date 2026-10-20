package com.wxx.aidocumentagent.conversation.application;

import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.api.dto.SendConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.rag.application.RagQueryExecution;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型已经返回最终结果后，短事务内锁定会话并原子写入 USER + ASSISTANT 两条消息。
 * 因而模型调用失败或任何持久化失败都不会留下半条助手消息。
 */
@Service
@ConditionalOnProperty(prefix = "app.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationTurnPersistenceService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationMessagePayloadCodec payloadCodec;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationTurnPersistenceService(ConversationRepository conversationRepository,
                                              ConversationMessageRepository messageRepository,
                                              ConversationMessagePayloadCodec payloadCodec,
                                              ApplicationEventPublisher eventPublisher) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository不能为空");
        this.messageRepository = Objects.requireNonNull(messageRepository, "conversationMessageRepository不能为空");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "conversationMessagePayloadCodec不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher不能为空");
    }

    @Transactional
    public SendConversationMessageResponse persistCompletedTurn(long conversationId, long knowledgeBaseId,
                                                                 SendConversationMessageCommand command,
                                                                 RagQueryExecution execution) {
        return persist(conversationId, knowledgeBaseId, command, execution.response().answer(),
                payloadCodec.encodeCitations(execution.response().citations()),
                execution.completion() == null ? null : execution.completion().modelName(),
                payloadCodec.encodeTokenUsage(execution.completion()), null, null, null);
    }

    @Transactional
    public SendConversationMessageResponse persistCompletedAgentTurn(long conversationId, long knowledgeBaseId,
                                                                      SendConversationMessageCommand command,
                                                                      AgentResult result,
                                                                      AgentExecutionContext context) {
        if (result.status() != AgentResult.Status.COMPLETED || result.citations().stream()
                .anyMatch(citation -> citation.knowledgeBaseId() != knowledgeBaseId)) {
            throw new IllegalArgumentException("Agent结果不合法");
        }
        List<RagCitationResponse> citations = result.citations().stream()
                .map(citation -> new RagCitationResponse(citation.citationId(), citation.documentId(),
                        citation.documentName(), citation.chunkId(), citation.pageFrom(), citation.pageTo(),
                        citation.quote()))
                .toList();
        return persist(conversationId, knowledgeBaseId, command, result.answer(),
                payloadCodec.encodeCitations(citations), result.modelName(),
                payloadCodec.encodeTokenUsage(result.usage()), payloadCodec.encodeAgentSteps(result.steps()),
                payloadCodec.encodeToolCalls(result.toolCalls()), Objects.requireNonNull(context));
    }

    private SendConversationMessageResponse persist(long conversationId, long knowledgeBaseId,
                                                    SendConversationMessageCommand command, String answer,
                                                    tools.jackson.databind.JsonNode citationsJson,
                                                    String modelName, tools.jackson.databind.JsonNode tokenUsageJson,
                                                    tools.jackson.databind.JsonNode agentStepsJson,
                                                    tools.jackson.databind.JsonNode toolCallsJson,
                                                    AgentExecutionContext context) {
        if (context != null) {
            context.checkActive();
        }
        Conversation conversation = conversationRepository.findLockedByIdAndKnowledgeBaseId(conversationId,
                knowledgeBaseId).orElseThrow(() -> new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
        long firstSequence = conversation.reserveMessageSequences(2);
        ConversationMessage user = ConversationMessage.user(conversationId, firstSequence, command.content());
        ConversationMessage assistant = ConversationMessage.assistant(conversationId, firstSequence + 1L,
                answer, citationsJson, modelName, tokenUsageJson, agentStepsJson, toolCallsJson);
        List<ConversationMessage> saved = messageRepository.saveAllAndFlush(List.of(user, assistant));
        if (saved.size() != 2) {
            throw new IllegalStateException("会话消息持久化结果不完整");
        }
        if (context != null) {
            // 在事务返回前再次校验；超时抛出异常会回滚 USER + ASSISTANT 两条消息。
            context.checkActive();
        }
        eventPublisher.publishEvent(new ConversationTurnPersistedEvent(conversationId, knowledgeBaseId,
                conversation.getLastMessageSequence()));
        return new SendConversationMessageResponse(payloadCodec.toResponse(saved.get(0)),
                payloadCodec.toResponse(saved.get(1)));
    }
}
