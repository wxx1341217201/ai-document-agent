package com.wxx.aidocumentagent.agent.plan;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentConversationSupport;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentEvent;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentFailure;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentUsage;
import com.wxx.aidocumentagent.agent.DocumentAgent;
import com.wxx.aidocumentagent.agent.ModelFailureDiagnostics;
import com.wxx.aidocumentagent.agent.react.ToolExecutionContext;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** M16 一次请求的有限计划、执行、反思与修订。 */
public final class PlanExecuteReflectAgent implements DocumentAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteReflectAgent.class);
    private final KnowledgeBaseRepository knowledgeBases;
    private final ConversationRepository conversations;
    private final Planner planner;
    private final Executor executor;
    private final Reflector reflector;
    private final PlanModelClient model;
    private final PlanProperties properties;
    private final ExecutorService workers;

    public PlanExecuteReflectAgent(KnowledgeBaseRepository knowledgeBases, ConversationRepository conversations,
                                   Planner planner, Executor executor, Reflector reflector, PlanModelClient model,
                                   PlanProperties properties, ExecutorService workers) {
        this.knowledgeBases = Objects.requireNonNull(knowledgeBases);
        this.conversations = Objects.requireNonNull(conversations);
        this.planner = Objects.requireNonNull(planner);
        this.executor = Objects.requireNonNull(executor);
        this.reflector = Objects.requireNonNull(reflector);
        this.model = Objects.requireNonNull(model);
        this.properties = Objects.requireNonNull(properties);
        this.workers = Objects.requireNonNull(workers);
    }

    @Override public AgentMode mode() { return AgentMode.PLAN_EXECUTE_REFLECT; }

    @Override
    public AgentResult execute(AgentRequest request, AgentExecutionContext context) {
        if (request.mode() != mode() || request.question().length() > 2_000) {
            throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
        context.checkActive();
        if (!knowledgeBases.existsById(request.knowledgeBaseId())) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (request.conversationId() != null && !conversations.existsByIdAndKnowledgeBaseIdAndAgentMode(
                request.conversationId(), request.knowledgeBaseId(), ConversationAgentMode.PLAN_EXECUTE_REFLECT)) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND);
        }
        Instant localDeadline = Instant.now().plus(properties.getDeadline());
        Instant deadline = context.deadline().isBefore(localDeadline) ? context.deadline() : localDeadline;
        ToolExecutionContext toolContext = new ToolExecutionContext(request.knowledgeBaseId(), context, deadline,
                properties.getMaxEvidenceChunks(), properties.getMaxEvidenceTokens(), properties.isStructuredAnswers()
                        ? properties.getMaxRegisteredSources() : properties.getMaxEvidenceChunks());
        toolContext.setGoal(request.question());
        Executor.RunState state = new Executor.RunState();
        BoundedModel bounded = new BoundedModel(context, deadline, toolContext);
        context.publish(mode(), AgentEvent.Kind.STARTED, null, null);
        String bestAnswer = null;
        List<AgentCitation> bestCitations = List.of();
        Reflector.Report report = null;
        List<String> gaps = List.of();
        try {
            for (int revision = 0; revision <= properties.getMaxRevisions(); revision++) {
                if (revision > 0) toolContext.focusOn(gaps);
                Plan plan = planner.plan(request.question(), AgentConversationSupport.history(context.memory().messages()),
                        gaps, bounded);
                AgentStepSummary planStep = new AgentStepSummary("r" + revision + "_plan", AgentStepSummary.Kind.PLAN,
                        "validated_plan", AgentStepSummary.Status.SUCCEEDED, null);
                state.steps.add(planStep);
                context.checkpointStep(planStep);
                context.publish(mode(), AgentEvent.Kind.STEP_UPDATED, "r" + revision + "_plan", null);
                Executor.Outcome outcome = executor.execute(plan, revision, toolContext, context, bounded, state);
                report = reflector.reflect(plan, outcome, revision, properties.getMaxRevisions());
                if (properties.isVerifyEvidence() && report.goalCovered() && outcome.stopCode() == null) {
                    var coverage = new EvidenceCoverageVerifier().verify(request.question(), outcome, toolContext, bounded, state.claimBinding());
                    if (!coverage.covered()) {
                        report = new Reflector.Report(false, coverage.missing(), report.citationIssues(),
                                revision < properties.getMaxRevisions());
                    }
                }
                AgentStepSummary reflectStep = new AgentStepSummary("r" + revision + "_reflect",
                        AgentStepSummary.Kind.REFLECTION,
                        report.goalCovered() ? "coverage_complete" : "coverage_gap",
                        AgentStepSummary.Status.SUCCEEDED, null);
                state.steps.add(reflectStep);
                context.checkpointStep(reflectStep);
                context.publish(mode(), AgentEvent.Kind.STEP_UPDATED, "r" + revision + "_reflect", null);
                if (outcome.answer() != null) {
                    bestAnswer = outcome.answer();
                    bestCitations = outcome.citations();
                }
                if (outcome.stopCode() != null) {
                    return failed(outcome.stopCode(), state, bounded);
                }
                if (!report.needsRevision()) break;
                if (state.claimBinding() != null) toolContext.retainBoundSources(state.claimBinding().bindings());
                if (state.toolCalls() >= properties.getMaxToolCalls()) {
                    return failed(AgentErrorCode.TOOL_CALL_LIMIT_EXCEEDED, state, bounded);
                }
                gaps = report.evidenceGaps();
            }
        }
        catch (BusinessException exception) {
            AgentErrorCode code = exception.getErrorCode() instanceof AgentErrorCode agentCode
                    ? agentCode : AgentErrorCode.EXECUTION_FAILED;
            return failed(code, state, bounded);
        }
        if (bestAnswer == null) {
            return failed(report != null && !report.citationIssues().isEmpty()
                    ? AgentErrorCode.INVALID_ANSWER : AgentErrorCode.EXECUTION_FAILED, state, bounded);
        }
        if (report != null && !report.goalCovered() && !bestCitations.isEmpty()) {
            if (properties.isStructuredAnswers()) return failed(AgentErrorCode.INVALID_ANSWER, state, bounded);
            bestAnswer += "\n\n部分检索未完成，以上结论可能不完整。";
        }
        return new AgentResult(bestAnswer, bestCitations, state.steps(), state.audits(),
                AgentResult.Status.COMPLETED, bounded.usage(state.toolCalls()), null, bounded.modelName);
    }

    private AgentResult failed(AgentErrorCode code, Executor.RunState state, BoundedModel bounded) {
        AgentResult.Status status = switch (code) {
            case DEADLINE_EXCEEDED, MODEL_TIMEOUT, TOOL_TIMEOUT -> AgentResult.Status.TIMED_OUT;
            case EXECUTION_CANCELLED -> AgentResult.Status.CANCELLED;
            default -> AgentResult.Status.FAILED;
        };
        return new AgentResult(null, List.of(), state.steps(), state.audits(), status,
                bounded.usage(state.toolCalls()), new AgentFailure(code), bounded.modelName);
    }

    private final class BoundedModel implements PlanModelClient {
        private final AgentExecutionContext context;
        private final Instant deadline;
        private final ToolExecutionContext toolContext;
        private int calls;
        private Integer prompt;
        private Integer completion;
        private Integer total;
        private boolean promptKnown = true;
        private boolean completionKnown = true;
        private boolean totalKnown = true;
        private String modelName;

        private BoundedModel(AgentExecutionContext context, Instant deadline, ToolExecutionContext toolContext) {
            this.context = context;
            this.deadline = deadline;
            this.toolContext = toolContext;
        }

        @Override
        public Reply complete(String system, String user) {
            return complete(Phase.SYNTHESIZE, system, user);
        }

        @Override
        public Reply complete(Phase phase, String system, String user) {
            return complete(phase, system, user, phase == Phase.PLAN || phase == Phase.REPAIR ? List.of()
                    : toolContext.citations().stream().map(AgentCitation::citationId).toList());
        }

        @Override
        public Reply complete(Phase phase, String system, String user, List<String> sourceIds) {
            // 一次受控重试计入真实模型调用预算；不会重试权限、参数、取消或超时错误。
            for (int attempt = 0; attempt <= properties.getMaxTransientRetries(); attempt++) {
                try {
                    return callOnce(phase, system, user, sourceIds);
                }
                catch (RetryableFailure failure) {
                    if (attempt == properties.getMaxTransientRetries() || calls >= properties.getMaxModelCalls()
                            || !Instant.now().isBefore(deadline)) {
                        throw new BusinessException(AgentErrorCode.MODEL_UNAVAILABLE);
                    }
                    context.checkActive();
                }
            }
            throw new BusinessException(AgentErrorCode.MODEL_UNAVAILABLE);
        }

        private Reply callOnce(Phase phase, String system, String user, List<String> sourceIds) {
            context.checkActive();
            if (calls >= properties.getMaxModelCalls()) {
                throw new BusinessException(AgentErrorCode.MODEL_CALL_LIMIT_EXCEEDED);
            }
            Duration remaining = Duration.between(Instant.now(), deadline);
            Duration timeout = remaining.compareTo(properties.getModelTimeout()) < 0
                    ? remaining : properties.getModelTimeout();
            if (timeout.isZero() || timeout.isNegative()) throw new BusinessException(AgentErrorCode.MODEL_TIMEOUT);
            calls++;
            context.observeModelInput(phase.name(), system, user, sourceIds,
                    phase == Phase.SYNTHESIZE ? toolContext.fingerprints() : List.of());
            long started = System.nanoTime();
            String status = "FAILED";
            Future<Reply> future = workers.submit(() -> model.complete(phase, system, user));
            try {
                Reply reply = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                if (reply == null || reply.text() == null || reply.text().isBlank()) {
                    modelFailure(phase, ModelFailureDiagnostics.emptyResponse());
                    throw new RetryableFailure();
                }
                if (reply.modelName() != null && !reply.modelName().isBlank()) modelName = reply.modelName();
                if (reply.promptTokens() == null) promptKnown = false;
                else prompt = (prompt == null ? 0 : prompt) + reply.promptTokens();
                if (reply.completionTokens() == null) completionKnown = false;
                else completion = (completion == null ? 0 : completion) + reply.completionTokens();
                if (reply.totalTokens() == null) totalKnown = false;
                else total = (total == null ? 0 : total) + reply.totalTokens();
                status = "SUCCEEDED";
                return reply;
            }
            catch (TimeoutException exception) {
                status = "TIMED_OUT";
                future.cancel(true);
                throw new BusinessException(AgentErrorCode.MODEL_TIMEOUT);
            }
            catch (InterruptedException exception) {
                status = "CANCELLED";
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new BusinessException(AgentErrorCode.EXECUTION_CANCELLED);
            }
            catch (ExecutionException exception) {
                var failure = ModelFailureDiagnostics.classify(exception.getCause());
                modelFailure(phase, failure);
                if (failure.retryable()) throw new RetryableFailure();
                throw new BusinessException(AgentErrorCode.MODEL_UNAVAILABLE);
            }
            finally {
                log.info("agent_model traceId={} phase={} call={} elapsedMillis={} status={}",
                        context.traceId(), phase, calls,
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started)), status);
            }
        }

        private void modelFailure(Phase phase, ModelFailureDiagnostics failure) {
            promptKnown = completionKnown = totalKnown = false;
            log.warn("agent_model_failure traceId={} phase={} call={} category={} httpStatus={} retryable={}",
                    context.traceId(), phase, calls, failure.category(), failure.httpStatus(), failure.retryable());
        }

        AgentUsage usage(int tools) {
            return new AgentUsage(promptKnown ? prompt : null, completionKnown ? completion : null,
                    totalKnown ? total : null, calls, tools);
        }
    }

    private static final class RetryableFailure extends RuntimeException { }
}
