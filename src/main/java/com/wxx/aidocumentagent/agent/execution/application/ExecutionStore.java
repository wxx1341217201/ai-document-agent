package com.wxx.aidocumentagent.agent.execution.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionPageResponse;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.agent.execution.ExecutionErrorCode;
import com.wxx.aidocumentagent.agent.execution.ExecutionProperties;
import com.wxx.aidocumentagent.agent.execution.ExecutionStatus;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.ToolInvocation;
import com.wxx.aidocumentagent.agent.execution.persistence.ToolInvocationRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.progress.ProgressEmitter;
import com.wxx.aidocumentagent.progress.ProgressEventType;
import com.wxx.aidocumentagent.progress.ProgressPayload;
import com.wxx.aidocumentagent.progress.ProgressStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small transactions around durable state changes. Model and tool calls never run in these transactions. */
@Service
@ConditionalOnProperty(prefix = "app.agent.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionStore {
    private static final Set<String> SAFE_ARGUMENT_KEYS = Set.of("queryLength", "topK", "chunkId", "radius", "documentId");
    private final AgentExecutionRepository executions;
    private final AgentStepExecutionRepository steps;
    private final ToolInvocationRepository tools;
    private final ExecutionProperties properties;
    private final ObjectMapper mapper;
    private final RetrievalFirstProperties retrieval;
    private final ReActProperties react;
    private final PlanProperties plan;
    private ProgressEmitter progress;

    public ExecutionStore(AgentExecutionRepository executions, AgentStepExecutionRepository steps,
                          ToolInvocationRepository tools, ExecutionProperties properties, ObjectMapper mapper,
                          RetrievalFirstProperties retrieval, ReActProperties react, PlanProperties plan) {
        this.executions = executions;
        this.steps = steps;
        this.tools = tools;
        this.properties = properties;
        this.mapper = mapper;
        this.retrieval = retrieval;
        this.react = react;
        this.plan = plan;
    }

    @Autowired(required = false)
    public void setProgressEmitter(ProgressEmitter progress) { this.progress = progress; }

    @Transactional(readOnly = true)
    public ExecutionPageResponse list(int page, int size, ExecutionStatus status, AgentMode mode) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt", "executionId"));
        Page<AgentExecutionRepository.Summary> result;
        if (status != null && mode != null) result = executions.findAllProjectedByStatusAndAgentMode(status, mode, pageable);
        else if (status != null) result = executions.findAllProjectedByStatus(status, pageable);
        else if (mode != null) result = executions.findAllProjectedByAgentMode(mode, pageable);
        else result = executions.findAllProjectedBy(pageable);
        return new ExecutionPageResponse(result.getContent().stream().map(item -> new ExecutionSummaryResponse(
                item.getExecutionId(), item.getConversationId(), item.getKnowledgeBaseId(), item.getAgentMode(),
                item.getStatus(), item.getCurrentStep(), item.getStartedAt(), item.getUpdatedAt())).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public AgentExecution create(String key, String hash, long knowledgeBaseId, Long conversationId,
                                 com.wxx.aidocumentagent.agent.AgentMode mode, String question, JsonNode parameters) {
        AgentExecution execution = AgentExecution.create(key, hash, knowledgeBaseId, conversationId, mode, question,
                parameters, LocalDateTime.now().plus(properties.getRecoveryWindow()));
        executions.saveAndFlush(execution);
        steps.save(AgentStepExecution.agentRun(execution.getExecutionId(), knowledgeBaseId));
        return execution;
    }

    @Transactional(readOnly = true)
    public Optional<AgentExecution> findByRequestKey(String key, long knowledgeBaseId) {
        return executions.findByRequestKeyAndKnowledgeBaseId(key, knowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public AgentExecution get(String id, long knowledgeBaseId) {
        return executions.findByExecutionIdAndKnowledgeBaseId(id, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ExecutionErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<AgentStepExecution> steps(String id, long knowledgeBaseId) {
        return steps.findByExecutionIdAndKnowledgeBaseIdOrderByIdAsc(id, knowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public List<ToolInvocation> tools(String id, long knowledgeBaseId) {
        return tools.findByExecutionIdAndKnowledgeBaseIdOrderByIdAsc(id, knowledgeBaseId);
    }

    @Transactional
    public String claim(String id, long knowledgeBaseId, boolean resume) {
        AgentExecution execution = locked(id, knowledgeBaseId);
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(execution.getDeadlineAt()) && execution.getResultJson() == null) {
            throw new BusinessException(ExecutionErrorCode.EXECUTION_EXPIRED);
        }
        try {
            java.time.Duration modeDeadline = switch (execution.getAgentMode()) {
                case RETRIEVAL_FIRST -> retrieval.getDeadline();
                case REACT -> react.getDeadline();
                case PLAN_EXECUTE_REFLECT -> plan.getDeadline();
            };
            java.time.Duration lease = properties.getLease().compareTo(modeDeadline.plusSeconds(30)) >= 0
                    ? properties.getLease() : modeDeadline.plusSeconds(30);
            String token = execution.claim(now, now.plus(lease), resume);
            // Final-result recovery only finalizes durable state; the Agent step already completed.
            if (execution.getResultJson() == null) {
                steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(id, knowledgeBaseId, "agent_run")
                        .ifPresent(AgentStepExecution::markRunning);
            }
            executions.flush();
            emit(execution, ProgressEventType.EXECUTION_STARTED,
                    ProgressPayload.agent(null, "RUNNING", "执行已开始", null, null));
            return token;
        }
        catch (IllegalStateException exception) {
            throw new BusinessException(ExecutionErrorCode.STATE_CONFLICT);
        }
    }

    /** The result write is the safe checkpoint. A later resume skips the Agent if this commit succeeded. */
    @Transactional
    public void checkpoint(String id, long knowledgeBaseId, String token, AgentResult result) {
        AgentExecution execution = owned(id, knowledgeBaseId, token);
        if (result.status() != AgentResult.Status.COMPLETED) throw new IllegalArgumentException("结果未完成");
        AgentResult safe = safeResult(result);
        execution.checkpoint(mapper.valueToTree(safe));
        steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(id, knowledgeBaseId, "agent_run")
                .orElseThrow(() -> new BusinessException(ExecutionErrorCode.STORAGE_FAILED)).markSucceeded();
        recordSummaries(execution, token, safe);
        executions.flush();
    }

    @Transactional
    public void complete(String id, long knowledgeBaseId, String token) {
        AgentExecution execution = owned(id, knowledgeBaseId, token);
        execution.finishSuccess(LocalDateTime.now());
        executions.flush();
        terminal(execution);
    }

    @Transactional
    public void failResult(String id, long knowledgeBaseId, String token, AgentResult result, boolean retryable) {
        AgentExecution execution = owned(id, knowledgeBaseId, token);
        String code = result.failure().code().name();
        recordSummaries(execution, token, safeResult(result));
        steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(id, knowledgeBaseId, "agent_run")
                .orElseThrow(() -> new BusinessException(ExecutionErrorCode.STORAGE_FAILED)).markFailed(code);
        execution.finishFailure(code, retryable, LocalDateTime.now());
        executions.flush();
        terminal(execution);
    }

    @Transactional
    public void fail(String id, long knowledgeBaseId, String token, String code, boolean retryable) {
        AgentExecution execution = owned(id, knowledgeBaseId, token);
        steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(id, knowledgeBaseId, "agent_run")
                .orElseThrow(() -> new BusinessException(ExecutionErrorCode.STORAGE_FAILED)).markFailed(code);
        execution.finishFailure(code, retryable, LocalDateTime.now());
        executions.flush();
        terminal(execution);
    }

    @Transactional
    public AgentExecution cancel(String id, long knowledgeBaseId) {
        AgentExecution execution = locked(id, knowledgeBaseId);
        try {
            execution.requestCancel(LocalDateTime.now());
        }
        catch (IllegalStateException exception) {
            throw new BusinessException(ExecutionErrorCode.STATE_CONFLICT);
        }
        executions.flush();
        if (execution.getStatus() == ExecutionStatus.CANCELLED) terminal(execution);
        return execution;
    }

    @Transactional
    public void pauseClaim(String id, long knowledgeBaseId, String token) {
        AgentExecution execution = owned(id, knowledgeBaseId, token);
        execution.pauseClaim(LocalDateTime.now());
        executions.flush();
        if (execution.getStatus() == ExecutionStatus.CANCELLED) terminal(execution);
    }

    @Transactional
    public void recordStep(String id, long knowledgeBaseId, String token, AgentStepSummary summary) {
        AgentExecution execution = owned(id, knowledgeBaseId, token);
        execution.progress(summary.stepId(), LocalDateTime.now().plus(properties.getLease()));
        JsonNode dependencies = mapper.valueToTree(summary.dependsOn());
        AgentStepExecution step = steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(id, knowledgeBaseId,
                summary.stepId()).orElseGet(() -> AgentStepExecution.from(id, knowledgeBaseId, summary, dependencies));
        step.apply(summary, dependencies);
        steps.save(step);
        executions.flush();
        ProgressEventType eventType = summary.kind() == AgentStepSummary.Kind.PLAN
                && summary.status() == AgentStepSummary.Status.SUCCEEDED ? ProgressEventType.PLAN_CREATED
                : summary.status() == AgentStepSummary.Status.RUNNING ? ProgressEventType.STEP_STARTED
                : summary.status() == AgentStepSummary.Status.PENDING ? null : ProgressEventType.STEP_COMPLETED;
        if (eventType != null) emit(execution, eventType, ProgressPayload.agent(summary.stepId(),
                summary.status().name(), eventType == ProgressEventType.PLAN_CREATED ? "计划已创建" : "步骤状态已更新",
                null, summary.errorCode() == null ? null : summary.errorCode().name()));
    }

    @Transactional
    public void recordTool(String id, long knowledgeBaseId, String token, String stepId,
                           AgentToolCallSummary summary) {
        AgentExecution execution = owned(id, knowledgeBaseId, token);
        if (!stepId.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new BusinessException(ExecutionErrorCode.STORAGE_FAILED);
        }
        execution.progress(stepId, LocalDateTime.now().plus(properties.getLease()));
        AgentToolCallSummary safe = safeTool(summary);
        tools.save(ToolInvocation.readOnly(id, knowledgeBaseId, token + ":" + stepId, safe,
                mapper.valueToTree(safe.argumentsSummary())));
        executions.flush();
        emit(execution, ProgressEventType.TOOL_COMPLETED, ProgressPayload.agent(stepId, safe.status().name(),
                "工具调用已结束", safe.toolName(), safe.errorCode() == null ? null : safe.errorCode().name()));
    }

    @Transactional(readOnly = true)
    public boolean cancelRequested(String id, long knowledgeBaseId, String token) {
        AgentExecution execution = get(id, knowledgeBaseId);
        return !execution.ownedBy(token) || execution.isCancelRequested();
    }

    @Transactional
    public void recover(String id, long knowledgeBaseId, LocalDateTime now) {
        AgentExecution execution = locked(id, knowledgeBaseId);
        if (execution.getStatus() == ExecutionStatus.RUNNING && execution.getLeaseUntil() != null
                && !execution.getLeaseUntil().isAfter(now)) {
            execution.recover(now);
            executions.flush();
            if (terminalStatus(execution.getStatus())) terminal(execution);
        }
    }

    @Transactional(readOnly = true)
    public List<AgentExecution> stale(LocalDateTime now, org.springframework.data.domain.Pageable page) {
        return executions.findByStatusAndLeaseUntilBefore(ExecutionStatus.RUNNING, now, page);
    }

    @Transactional(readOnly = true)
    public List<AgentExecution> pending(org.springframework.data.domain.Pageable page) {
        return executions.findByStatusOrderByCreatedAtAsc(ExecutionStatus.PENDING, page);
    }

    @Transactional(readOnly = true)
    public List<AgentExecution> expiredWaiting(LocalDateTime now, org.springframework.data.domain.Pageable page) {
        return executions.findByStatusInAndDeadlineAtBefore(List.of(ExecutionStatus.PENDING, ExecutionStatus.PAUSED),
                now, page);
    }

    @Transactional
    public void expireWaiting(String id, long knowledgeBaseId, LocalDateTime now) {
        AgentExecution execution = locked(id, knowledgeBaseId);
        ExecutionStatus before = execution.getStatus();
        execution.expireWaiting(now);
        executions.flush();
        if (before != execution.getStatus() && terminalStatus(execution.getStatus())) terminal(execution);
    }

    private void terminal(AgentExecution execution) {
        boolean success = execution.getStatus() == ExecutionStatus.SUCCEEDED;
        emit(execution, success ? ProgressEventType.EXECUTION_COMPLETED : ProgressEventType.EXECUTION_FAILED,
                ProgressPayload.agent(null, execution.getStatus().name(), success ? "执行已完成" : "执行已结束",
                        null, safeCode(execution.getErrorCode())));
    }

    private boolean terminalStatus(ExecutionStatus status) {
        return status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.FAILED
                || status == ExecutionStatus.CANCELLED;
    }

    private String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : null;
    }

    private void emit(AgentExecution execution, ProgressEventType type, ProgressPayload payload) {
        if (progress != null) progress.afterCommit(
                ProgressStream.agent(execution.getKnowledgeBaseId(), execution.getExecutionId()), type, payload);
    }

    private void recordSummaries(AgentExecution execution, String token, AgentResult result) {
        String id = execution.getExecutionId();
        long knowledgeBaseId = execution.getKnowledgeBaseId();
        for (AgentStepSummary summary : result.steps()) {
            JsonNode dependencies = mapper.valueToTree(summary.dependsOn());
            AgentStepExecution step = steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(id, knowledgeBaseId,
                    summary.stepId()).orElseGet(() -> AgentStepExecution.from(id, knowledgeBaseId, summary, dependencies));
            step.apply(summary, dependencies);
            steps.save(step);
        }
        boolean recordedDuringRun = tools.findByExecutionIdAndKnowledgeBaseIdOrderByIdAsc(id, knowledgeBaseId)
                .stream().anyMatch(item -> item.getInvocationKey().startsWith(token + ":"));
        if (!recordedDuringRun) {
            for (int i = 0; i < result.toolCalls().size(); i++) {
                String key = token + ":final:" + i;
                tools.save(ToolInvocation.readOnly(id, knowledgeBaseId, key, result.toolCalls().get(i),
                        mapper.valueToTree(result.toolCalls().get(i).argumentsSummary())));
            }
        }
    }

    private AgentResult safeResult(AgentResult result) {
        if (result.answer() != null && result.answer().toLowerCase(java.util.Locale.ROOT).contains("<think")) {
            throw new BusinessException(AgentErrorCode.INVALID_ANSWER);
        }
        return new AgentResult(result.answer(), result.citations(), result.steps(),
                result.toolCalls().stream().map(this::safeTool).toList(), result.status(), result.usage(),
                result.failure(), result.modelName());
    }

    private AgentToolCallSummary safeTool(AgentToolCallSummary call) {
        Map<String, String> summary = call.argumentsSummary().entrySet().stream()
                .filter(entry -> SAFE_ARGUMENT_KEYS.contains(entry.getKey())
                        && entry.getValue() != null && entry.getValue().matches("[0-9]{1,20}"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new AgentToolCallSummary(call.toolName(), call.status(), call.elapsedMillis(), call.errorCode(),
                summary);
    }

    private AgentExecution locked(String id, long knowledgeBaseId) {
        return executions.findLocked(id, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ExecutionErrorCode.NOT_FOUND));
    }

    private AgentExecution owned(String id, long knowledgeBaseId, String token) {
        AgentExecution execution = locked(id, knowledgeBaseId);
        if (!execution.ownedBy(token)) throw new BusinessException(ExecutionErrorCode.STATE_CONFLICT);
        return execution;
    }
}
