package com.wxx.aidocumentagent.agent.execution.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wxx.aidocumentagent.agent.AgentCancellationSignal;
import com.wxx.aidocumentagent.agent.AgentCheckpointSink;
import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentEventPublisher;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.execution.ExecutionErrorCode;
import com.wxx.aidocumentagent.agent.execution.ExecutionCheckpointObserver;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.application.ConversationMemoryService;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.Conversation;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs one claimed execution without holding a database transaction over model or tool calls. */
@Component
@ConditionalOnProperty(prefix = "app.agent.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionRunner {
    private static final Logger log = LoggerFactory.getLogger(ExecutionRunner.class);
    private final ExecutionStore store;
    private final AgentRouter router;
    private final AgentEventPublisher events;
    private final ConversationRepository conversations;
    private final ObjectProvider<ConversationMemoryService> memory;
    private final RetrievalFirstProperties retrieval;
    private final ReActProperties react;
    private final PlanProperties plan;
    private ExecutionCheckpointObserver checkpointObserver = ExecutionCheckpointObserver.NO_OP;

    @Autowired(required = false)
    public void setCheckpointObserver(ExecutionCheckpointObserver observer) {
        this.checkpointObserver = java.util.Objects.requireNonNull(observer);
    }

    public ExecutionRunner(ExecutionStore store, AgentRouter router, AgentEventPublisher events,
                           ConversationRepository conversations, ObjectProvider<ConversationMemoryService> memory,
                           RetrievalFirstProperties retrieval, ReActProperties react, PlanProperties plan) {
        this.store = store;
        this.router = router;
        this.events = events;
        this.conversations = conversations;
        this.memory = memory;
        this.retrieval = retrieval;
        this.react = react;
        this.plan = plan;
    }

    public void runPending(String id, long knowledgeBaseId) {
        String token;
        try {
            token = store.claim(id, knowledgeBaseId, false);
        }
        catch (BusinessException exception) {
            return; // Another worker claimed it, or its recovery window expired.
        }
        runClaimed(id, knowledgeBaseId, token);
    }

    public void runClaimed(String id, long knowledgeBaseId, String token) {
        try {
            AgentExecution execution = store.get(id, knowledgeBaseId);
            if (!execution.ownedBy(token)) return;
            if (execution.isCancelRequested()) {
                store.fail(id, knowledgeBaseId, token, AgentErrorCode.EXECUTION_CANCELLED.name(), false);
                return;
            }
            if (execution.getResultJson() != null) {
                store.complete(id, knowledgeBaseId, token);
                return;
            }
            AgentRequest request = new AgentRequest(execution.getQuestion(), knowledgeBaseId,
                    execution.getConversationId(), execution.getAgentMode(), Map.of());
            AgentConversationContext history = loadMemory(execution);
            Instant overall = execution.getDeadlineAt().atZone(ZoneId.systemDefault()).toInstant();
            Instant attempt = Instant.now().plus(switch (execution.getAgentMode()) {
                case RETRIEVAL_FIRST -> retrieval.getDeadline();
                case REACT -> react.getDeadline();
                case PLAN_EXECUTE_REFLECT -> plan.getDeadline();
            });
            Instant deadline = attempt.isBefore(overall) ? attempt : overall;
            AgentCancellationSignal cancellation = () -> store.cancelRequested(id, knowledgeBaseId, token);
            AgentCheckpointSink checkpoints = new AgentCheckpointSink() {
                @Override public void step(com.wxx.aidocumentagent.agent.AgentStepSummary summary) {
                    store.recordStep(id, knowledgeBaseId, token, summary);
                }
                @Override public void tool(String stepId,
                                           com.wxx.aidocumentagent.agent.AgentToolCallSummary summary) {
                    store.recordTool(id, knowledgeBaseId, token, stepId, summary);
                }
            };
            AgentExecutionContext context = AgentExecutionContext.forDurableRequest(request,
                    UUID.randomUUID().toString(), deadline, history, events, cancellation, id, checkpoints);
            AgentResult result = router.execute(request, context);
            if (result.status() == AgentResult.Status.COMPLETED) {
                store.checkpoint(id, knowledgeBaseId, token, result);
                checkpointObserver.afterResultCheckpoint(id, knowledgeBaseId);
                store.complete(id, knowledgeBaseId, token);
            }
            else {
                store.failResult(id, knowledgeBaseId, token, result, retryable(result.failure().code()));
            }
        }
        catch (BusinessException exception) {
            String code = exception.getErrorCode().code();
            safeFail(id, knowledgeBaseId, token, code,
                    exception.getErrorCode() instanceof AgentErrorCode agentCode && retryable(agentCode));
        }
        catch (RuntimeException exception) {
            log.error("持久Agent执行异常 executionId={} knowledgeBaseId={}", id, knowledgeBaseId, exception);
            safeFail(id, knowledgeBaseId, token, AgentErrorCode.EXECUTION_FAILED.name(), false);
        }
    }

    private AgentConversationContext loadMemory(AgentExecution execution) {
        Long conversationId = execution.getConversationId();
        long knowledgeBaseId = execution.getKnowledgeBaseId();
        if (conversationId == null) return new AgentConversationContext(knowledgeBaseId, null, List.of());
        Conversation conversation = conversations.findByIdAndKnowledgeBaseId(conversationId, knowledgeBaseId)
                .filter(found -> found.getAgentMode() == ConversationAgentMode.valueOf(execution.getAgentMode().name()))
                .orElseThrow(() -> new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
        ConversationMemoryService memoryService = memory.getIfAvailable();
        if (memoryService == null) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_ANSWERING_UNAVAILABLE);
        }
        List<AgentMemoryMessage> messages = memoryService.load(conversation).stream()
                .map(item -> new AgentMemoryMessage(item.sequenceNo(),
                        item.role() == ConversationMessageRole.USER ? AgentMemoryMessage.Role.USER
                                : AgentMemoryMessage.Role.ASSISTANT, item.content()))
                .toList();
        return new AgentConversationContext(knowledgeBaseId, conversationId, messages);
    }

    private boolean retryable(AgentErrorCode code) {
        return code == AgentErrorCode.MODEL_TIMEOUT || code == AgentErrorCode.MODEL_UNAVAILABLE
                || code == AgentErrorCode.TOOL_TIMEOUT || code == AgentErrorCode.TOOL_FAILED
                || code == AgentErrorCode.DEADLINE_EXCEEDED;
    }

    private void safeFail(String id, long knowledgeBaseId, String token, String code, boolean retryable) {
        try {
            // Once the Agent result is durable, let recovery finalize it without re-running the Agent.
            if (store.get(id, knowledgeBaseId).getResultJson() != null) return;
            store.fail(id, knowledgeBaseId, token, code, retryable);
        }
        catch (RuntimeException exception) {
            // A stale worker must never overwrite recovery or another worker's terminal state.
            log.warn("执行结果未被接纳 executionId={} knowledgeBaseId={} errorCode={}", id, knowledgeBaseId, code);
        }
    }
}
