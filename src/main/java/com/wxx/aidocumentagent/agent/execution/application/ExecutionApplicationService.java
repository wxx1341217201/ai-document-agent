package com.wxx.aidocumentagent.agent.execution.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.ExecutionErrorCode;
import com.wxx.aidocumentagent.agent.execution.ExecutionStatus;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionPageResponse;
import com.wxx.aidocumentagent.agent.execution.api.dto.CreateExecutionRequest;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionResponse;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.ToolInvocation;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.api.CommonErrorCode;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(prefix = "app.agent.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionApplicationService {
    private final ExecutionStore store;
    private final ExecutionRunner runner;
    private final ThreadPoolTaskExecutor worker;
    private final KnowledgeBaseRepository knowledgeBases;
    private final ConversationRepository conversations;
    private final ObjectMapper mapper;

    public ExecutionApplicationService(ExecutionStore store, ExecutionRunner runner,
                                       @Qualifier("executionWorker") ThreadPoolTaskExecutor executionWorker,
                                       KnowledgeBaseRepository knowledgeBases,
                                       ConversationRepository conversations, ObjectMapper mapper) {
        this.store = store;
        this.runner = runner;
        this.worker = executionWorker;
        this.knowledgeBases = knowledgeBases;
        this.conversations = conversations;
        this.mapper = mapper;
    }

    public ExecutionPageResponse list(int page, int size, ExecutionStatus status, AgentMode mode) {
        return store.list(page, size, status, mode);
    }

    public Created create(String idempotencyKey, CreateExecutionRequest input) {
        if (input == null || input.knowledgeBaseId() <= 0 || input.question() == null
                || input.question().isBlank() || input.question().strip().length() > 2000) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        long knowledgeBaseId = input.knowledgeBaseId();
        AgentMode mode = input.agentMode() == null ? AgentMode.RETRIEVAL_FIRST : input.agentMode();
        String question = input.question().strip();
        String key = normalizeKey(idempotencyKey);
        String hash = digest(input);
        var existing = store.findByRequestKey(key, knowledgeBaseId);
        if (existing.isPresent()) return duplicate(existing.get(), hash);
        requireScope(knowledgeBaseId, input.conversationId(), mode);
        AgentExecution execution;
        try {
            execution = store.create(key, hash, knowledgeBaseId, input.conversationId(), mode, question,
                    mapper.valueToTree(Map.of()));
        }
        catch (DataIntegrityViolationException exception) {
            return store.findByRequestKey(key, knowledgeBaseId)
                    .map(found -> duplicate(found, hash))
                    .orElseThrow(() -> new BusinessException(ExecutionErrorCode.REQUEST_KEY_CONFLICT));
        }
        dispatchPending(execution.getExecutionId(), knowledgeBaseId);
        return new Created(get(execution.getExecutionId(), knowledgeBaseId), true);
    }

    public ExecutionResponse get(String id, long knowledgeBaseId) {
        requireIdAndScope(id, knowledgeBaseId);
        return response(store.get(id, knowledgeBaseId));
    }

    public ExecutionResponse resume(String id, long knowledgeBaseId) {
        requireIdAndScope(id, knowledgeBaseId);
        String token = store.claim(id, knowledgeBaseId, true);
        try {
            worker.execute(() -> runner.runClaimed(id, knowledgeBaseId, token));
        }
        catch (TaskRejectedException exception) {
            store.pauseClaim(id, knowledgeBaseId, token);
            throw new BusinessException(ExecutionErrorCode.STATE_CONFLICT);
        }
        return get(id, knowledgeBaseId);
    }

    public ExecutionResponse cancel(String id, long knowledgeBaseId) {
        requireIdAndScope(id, knowledgeBaseId);
        store.cancel(id, knowledgeBaseId);
        return get(id, knowledgeBaseId);
    }

    public void dispatchPending(String id, long knowledgeBaseId) {
        try {
            worker.execute(() -> runner.runPending(id, knowledgeBaseId));
        }
        catch (TaskRejectedException ignored) {
            // The row remains PENDING and the scanner will dispatch it later.
        }
    }

    private Created duplicate(AgentExecution existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new BusinessException(ExecutionErrorCode.REQUEST_KEY_CONFLICT);
        }
        return new Created(get(existing.getExecutionId(), existing.getKnowledgeBaseId()), false);
    }

    private void requireScope(long knowledgeBaseId, Long conversationId, AgentMode mode) {
        if (!knowledgeBases.existsById(knowledgeBaseId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (conversationId != null && !conversations.existsByIdAndKnowledgeBaseIdAndAgentMode(conversationId,
                knowledgeBaseId, ConversationAgentMode.valueOf(mode.name()))) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND);
        }
    }

    private void requireIdAndScope(String id, long knowledgeBaseId) {
        if (knowledgeBaseId <= 0 || id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
    }

    private String normalizeKey(String value) {
        if (value == null) return "generated:" + UUID.randomUUID();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        return value;
    }

    private String digest(CreateExecutionRequest input) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("knowledgeBaseId", input.knowledgeBaseId());
            canonical.put("conversationId", input.conversationId());
            canonical.put("agentMode", input.agentMode() == null ? null : input.agentMode().name());
            canonical.put("question", input.question());
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private ExecutionResponse response(AgentExecution execution) {
        String id = execution.getExecutionId();
        long knowledgeBaseId = execution.getKnowledgeBaseId();
        var stepResponses = store.steps(id, knowledgeBaseId).stream().map(this::step).toList();
        var toolResponses = store.tools(id, knowledgeBaseId).stream().map(this::tool).toList();
        return new ExecutionResponse(id, knowledgeBaseId, execution.getConversationId(), execution.getAgentMode(),
                execution.getStatus(), execution.getCurrentStep(), execution.getVersion(), execution.getDeadlineAt(),
                execution.isCancelRequested(), execution.isRetryable(), execution.getResultJson(),
                execution.getErrorCode(), stepResponses, toolResponses, execution.getCreatedAt(),
                execution.getUpdatedAt());
    }

    private ExecutionResponse.Step step(AgentStepExecution step) {
        return new ExecutionResponse.Step(step.getStepId(), step.getKind(), step.getOperation(), step.getStatus(),
                step.getDependsOnJson(), step.getErrorCode());
    }

    private ExecutionResponse.Tool tool(ToolInvocation invocation) {
        return new ExecutionResponse.Tool(invocation.getInvocationKey(), invocation.getToolName(),
                invocation.getStatus(), invocation.getArgumentsSummaryJson(), invocation.getElapsedMillis(),
                invocation.getErrorCode());
    }

    public record Created(ExecutionResponse response, boolean created) { }
}
