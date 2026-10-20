package com.wxx.aidocumentagent.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.wxx.aidocumentagent.common.api.BusinessException;

/**
 * 与一个 AgentRequest 实例绑定的一次性执行上下文。记忆为不可变快照，取消信号也由本次请求持有。
 */
public final class AgentExecutionContext {

    private final AgentRequest request;
    private final String traceId;
    private final Instant deadline;
    private final AgentConversationContext memory;
    private final AgentEventPublisher eventPublisher;
    private final AgentCancellationSignal cancellationSignal;
    private final boolean completionDeferred;
    private final String durableExecutionId;
    private final AgentCheckpointSink checkpointSink;
    private final AgentExecutionProbe probe;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private AgentExecutionContext(AgentRequest request, String traceId, Instant deadline,
                                  AgentConversationContext memory, AgentEventPublisher eventPublisher,
                                  AgentCancellationSignal cancellationSignal, boolean completionDeferred,
                                  String durableExecutionId, AgentCheckpointSink checkpointSink) {
        this(request, traceId, deadline, memory, eventPublisher, cancellationSignal, completionDeferred,
                durableExecutionId, checkpointSink, AgentExecutionProbe.NO_OP);
    }

    private AgentExecutionContext(AgentRequest request, String traceId, Instant deadline,
                                  AgentConversationContext memory, AgentEventPublisher eventPublisher,
                                  AgentCancellationSignal cancellationSignal, boolean completionDeferred,
                                  String durableExecutionId, AgentCheckpointSink checkpointSink,
                                  AgentExecutionProbe probe) {
        this.request = Objects.requireNonNull(request, "request不能为空");
        if (traceId == null || !traceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("traceId不合法");
        }
        this.traceId = traceId;
        this.deadline = Objects.requireNonNull(deadline, "deadline不能为空");
        this.memory = Objects.requireNonNull(memory, "memory不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher不能为空");
        this.cancellationSignal = Objects.requireNonNull(cancellationSignal, "cancellationSignal不能为空");
        this.completionDeferred = completionDeferred;
        this.durableExecutionId = durableExecutionId;
        this.checkpointSink = Objects.requireNonNull(checkpointSink);
        this.probe = Objects.requireNonNull(probe);
        if (memory.knowledgeBaseId() != request.knowledgeBaseId()
                || !Objects.equals(memory.conversationId(), request.conversationId())) {
            throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
    }

    public static AgentExecutionContext forRequest(AgentRequest request, String traceId, Instant deadline,
                                                   AgentConversationContext memory,
                                                   AgentEventPublisher eventPublisher,
                                                   AgentCancellationSignal cancellationSignal) {
        return new AgentExecutionContext(request, traceId, deadline, memory, eventPublisher, cancellationSignal,
                false, null, AgentCheckpointSink.NO_OP);
    }

    public static AgentExecutionContext forObservedRequest(AgentRequest request, String traceId, Instant deadline,
                                                            AgentConversationContext memory,
                                                            AgentEventPublisher eventPublisher,
                                                            AgentCancellationSignal cancellationSignal,
                                                            AgentExecutionProbe probe) {
        return new AgentExecutionContext(request, traceId, deadline, memory, eventPublisher, cancellationSignal,
                false, null, AgentCheckpointSink.NO_OP, probe);
    }

    public void observeEvidence(AgentCitation citation, String contextText) {
        checkActive();
        if (citation.knowledgeBaseId() != request.knowledgeBaseId()) {
            throw new BusinessException(AgentErrorCode.SCOPE_VIOLATION);
        }
        probe.evidence(citation, contextText);
    }

    public void diagnostic(String stage, java.util.Map<String, Object> details) {
        checkActive();
        probe.diagnostic(stage, java.util.Map.copyOf(details));
    }

    public void observeModelInput(String stage, String system, String user, java.util.List<String> citationIds) {
        observeModelInput(stage, system, user, citationIds, java.util.List.of());
    }

    public void observeModelInput(String stage, String system, String user, java.util.List<String> citationIds,
                                  java.util.List<java.util.Map<String, Object>> evidenceSnapshots) {
        diagnostic("model_input", java.util.Map.of("phase", stage,
                "systemSha256", ClaimEvidenceValidator.sha256(system), "userSha256", ClaimEvidenceValidator.sha256(user),
                "characters", system.length() + user.length(), "citationIds", java.util.List.copyOf(citationIds),
                "evidenceSnapshots", java.util.List.copyOf(evidenceSnapshots)));
    }

    public AgentExecutionProbe.ToolAction beforeTool(String name, java.util.Map<String, String> summary) {
        checkActive();
        return Objects.requireNonNull(probe.beforeTool(request.knowledgeBaseId(), name, summary));
    }

    public void observeToolResult(String name, java.util.Map<String, String> summary) {
        checkActive();
        probe.toolResult(name, summary);
    }

    public static AgentExecutionContext forDurableRequest(AgentRequest request, String traceId, Instant deadline,
                                                          AgentConversationContext memory,
                                                          AgentEventPublisher eventPublisher,
                                                          AgentCancellationSignal cancellationSignal,
                                                          String executionId, AgentCheckpointSink checkpointSink) {
        return new AgentExecutionContext(request, traceId, deadline, memory, eventPublisher, cancellationSignal,
                false, Objects.requireNonNull(executionId), checkpointSink);
    }

    public static AgentExecutionContext forConversationTurn(AgentRequest request, String traceId, Instant deadline,
                                                            AgentConversationContext memory,
                                                            AgentEventPublisher eventPublisher,
                                                            AgentCancellationSignal cancellationSignal) {
        if (request.conversationId() == null) {
            throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
        return new AgentExecutionContext(request, traceId, deadline, memory, eventPublisher, cancellationSignal,
                true, null, AgentCheckpointSink.NO_OP);
    }

    void claimFor(AgentRequest executionRequest) {
        if (request != executionRequest) {
            throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
        if (!claimed.compareAndSet(false, true)) {
            throw new BusinessException(AgentErrorCode.CONTEXT_REUSED);
        }
    }

    public String traceId() {
        return traceId;
    }

    public Instant deadline() {
        return deadline;
    }

    public AgentConversationContext memory() {
        return memory;
    }

    public AgentCancellationSignal cancellationSignal() {
        return cancellationSignal;
    }

    public boolean completionDeferred() {
        return completionDeferred;
    }

    public String durableExecutionId() { return durableExecutionId; }

    public void checkpointStep(AgentStepSummary summary) {
        checkActive();
        checkpointSink.step(Objects.requireNonNull(summary));
        probe.step(summary);
    }

    public void checkpointTool(String stepId, AgentToolCallSummary summary) {
        checkActive();
        checkpointSink.tool(Objects.requireNonNull(stepId), Objects.requireNonNull(summary));
        probe.tool(summary);
    }

    public Duration remainingTime() {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public void checkActive() {
        if (cancellationSignal.isCancelled()) {
            throw new BusinessException(AgentErrorCode.EXECUTION_CANCELLED);
        }
        if (!Instant.now().isBefore(deadline)) {
            throw new BusinessException(AgentErrorCode.DEADLINE_EXCEEDED);
        }
    }

    public void publish(AgentMode mode, AgentEvent.Kind kind, String stepId, AgentErrorCode errorCode) {
        AgentMode selectedMode = request.mode() == null ? AgentMode.RETRIEVAL_FIRST : request.mode();
        if (mode != selectedMode) {
            throw new BusinessException(AgentErrorCode.INVALID_RESULT);
        }
        eventPublisher.publish(new AgentEvent(traceId, request.knowledgeBaseId(), request.conversationId(),
                mode, kind, stepId, errorCode, Instant.now()));
    }
}
