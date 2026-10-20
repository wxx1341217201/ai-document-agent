package com.wxx.aidocumentagent.agent.benchmark;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkDataset.Task;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkTrace.Dispatch;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.FactReview;
import tools.jackson.databind.ObjectMapper;

/** 自动结构门控 + M19B 事实/引用人工复核；任何一个任务门控失败都不能计为成功。 */
public final class AgentBenchmarkEvaluator {
    public static final String POLICY_VERSION = "m19c-human-final-v2";
    private static final Pattern TOKEN = Pattern.compile("\\[C[1-9]\\d*]");
    private static final Pattern SHAPED = Pattern.compile("\\[C[^]]*]", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper;
    private final RagAccuracyEvaluator answers;

    public AgentBenchmarkEvaluator(ObjectMapper mapper) { this.mapper = mapper; this.answers = new RagAccuracyEvaluator(mapper); }

    public String resultHash(String runId, String datasetSha, Task task, Observation observation) {
        return AgentBenchmarkText.canonicalHash(mapper, List.of(runId, datasetSha, task, observation));
    }

    public Evaluation evaluate(String runId, String datasetSha, Task task, Observation observation, Review review) {
        if (review != null) validateBinding(runId, datasetSha, task, observation, review);
        if (!observation.executed()) return new Evaluation(Status.NOT_RUN, null, List.of(observation.errorCode()), review);
        if (observation.errorCode() != null) return new Evaluation(Status.ERROR, null, List.of(observation.errorCode()), review);
        List<String> reasons = constraints(task, observation);
        Turn last = observation.turns().isEmpty() ? null : observation.turns().getLast();
        if (last == null || last.result() == null || last.result().status() != AgentResult.Status.COMPLETED) {
            return new Evaluation(Status.FAILED, null, distinct(reasons), review);
        }
        var answerObservation = answerObservation(last);
        var sample = task.answerSample();
        RagAccuracyEvaluator.Review answerReview = review == null ? null : new RagAccuracyEvaluator.Review(runId,
                datasetSha, task.caseId(), answers.resultHash(runId, datasetSha, sample, answerObservation),
                review.reviewer(), review.reviewedAt(), review.referenceVerified(), review.facts(),
                review.criticalConflictFree(), review.coreClaimsSupported(), review.refusalCorrect(), review.notes());
        var answerEvaluation = answers.evaluate(runId, datasetSha, sample, answerObservation, answerReview);
        if (answerEvaluation.status() != RagAccuracyEvaluator.Status.PENDING_REVIEW) reasons.addAll(answerEvaluation.failureReasons());
        if (review != null && Boolean.TRUE.equals(review.referenceVerified())) {
            if (!Boolean.TRUE.equals(review.allTurnsAcceptable())) reasons.add("INTERMEDIATE_TURNS_NOT_ACCEPTABLE");
            if (!Boolean.TRUE.equals(review.safeErrorHandling())) reasons.add("UNSAFE_ERROR_HANDLING");
        }
        boolean answerCorrect = answerEvaluation.status() == RagAccuracyEvaluator.Status.CORRECT
                || answerEvaluation.status() == RagAccuracyEvaluator.Status.REFUSAL_CORRECT;
        Status status = !reasons.isEmpty() ? Status.FAILED : answerCorrect ? Status.SUCCEEDED : Status.PENDING_REVIEW;
        if (status == Status.PENDING_REVIEW) reasons.addAll(answerEvaluation.failureReasons());
        return new Evaluation(status, answerEvaluation.reviewedFactCoverage(), distinct(reasons), review);
    }

    private List<String> constraints(Task task, Observation observation) {
        List<String> reasons = new ArrayList<>();
        if (observation.turns().size() != task.turns().size() || observation.turns().size() > task.maxTurns()) reasons.add("TURN_LIMIT_OR_INCOMPLETE_TASK");
        int tools = 0, models = 0, injections = 0;
        List<AgentMemoryMessage> history = new ArrayList<>();
        for (int i = 0; i < observation.turns().size(); i++) {
            Turn turn = observation.turns().get(i);
            if (turn.turn() != i + 1 || i >= task.turns().size() || !turn.question().equals(task.turns().get(i))) reasons.add("TURN_SCRIPT_MISMATCH");
            if (turn.inputMemoryMessages() != history.size()
                    || !Objects.equals(turn.inputMemorySha256(), AgentBenchmarkText.canonicalHash(mapper, history))) {
                reasons.add("ACTUAL_TURN_MEMORY_MISMATCH:T" + turn.turn());
            }
            if (turn.result() == null || turn.result().status() != task.expectedStatus()) reasons.add("TASK_STATE_MISMATCH:T" + turn.turn());
            if (!turn.evidenceVerified()) reasons.add("UNVERIFIED_RETRIEVAL_CONTEXT:T" + turn.turn());
            if (turn.hiddenReasoningDetected()) reasons.add("HIDDEN_REASONING_REJECTED:T" + turn.turn());
            if (turn.errorCode() != null) reasons.add(turn.errorCode());
            tools += turn.tools().size();
            if (turn.result() != null) {
                Integer calls = turn.result().usage().modelCalls();
                if (calls == null) reasons.add("UNKNOWN_MODEL_CALL_BUDGET");
                else models += calls;
                if (!Objects.equals(turn.result().usage().toolCalls(), turn.tools().size())) reasons.add("TOOL_AUDIT_COUNT_MISMATCH");
                if (!turn.result().toolCalls().equals(turn.tools()) || !turn.result().steps().equals(turn.steps())) reasons.add("RESULT_TRACE_MISMATCH");
                citationChecks(task.knowledgeBaseId(), turn, reasons);
            }
            for (Dispatch dispatch : turn.dispatches()) {
                if (dispatch.knowledgeBaseId() != task.knowledgeBaseId()) reasons.add("CROSS_KNOWLEDGE_BASE_ACCESS");
                if (dispatch.action() != ToolAction.RUN) {
                    injections++;
                    var fault = task.fault();
                    if (fault == null || fault.turn() != turn.turn() || !fault.toolName().equals(dispatch.toolName())
                            || fault.occurrence() != dispatch.occurrence() || fault.action() != dispatch.action()) reasons.add("UNDECLARED_FAULT_INJECTION");
                    if (dispatch.action() == ToolAction.FAIL && turn.tools().stream().noneMatch(a -> a.toolName().equals(dispatch.toolName())
                            && a.errorCode() == com.wxx.aidocumentagent.agent.AgentErrorCode.TOOL_FAILED)) reasons.add("FAULT_ERROR_NOT_AUDITED");
                }
            }
            long replans = replans(turn);
            if (replans > task.maxReplansPerTurn() || (task.mode() != AgentMode.PLAN_EXECUTE_REFLECT && replans > 0)) reasons.add("REPLAN_LIMIT_EXCEEDED");
            if (turn.turn() == task.turns().size() && (replans < task.minFinalReplans()
                    || (task.minFinalReplans() > 0 && turn.steps().stream().noneMatch(s -> s.stepId().equals("r0_reflect")
                        && s.operation().equals("coverage_gap"))))) reasons.add("REQUIRED_REPLAN_NOT_EXERCISED");
            if (turn.result() != null && turn.result().status() == AgentResult.Status.COMPLETED) {
                history.add(new AgentMemoryMessage(history.size() + 1L, AgentMemoryMessage.Role.USER, turn.question()));
                history.add(new AgentMemoryMessage(history.size() + 1L, AgentMemoryMessage.Role.ASSISTANT, turn.result().answer()));
            }
        }
        if (tools > task.maxToolCalls()) reasons.add("TASK_TOOL_BUDGET_EXCEEDED");
        if (models > task.maxModelCalls()) reasons.add("TASK_MODEL_BUDGET_EXCEEDED");
        if (injections != (task.fault() == null ? 0 : 1)) reasons.add("REQUIRED_FAULT_NOT_EXERCISED_ONCE");
        if (!observation.turns().isEmpty()) {
            Turn last = observation.turns().getLast();
            for (var requirement : task.minimumSuccessfulToolCalls().entrySet()) {
                long audited = last.tools().stream().filter(t -> t.toolName().equals(requirement.getKey()) && successful(t.status())).count();
                long returned = last.dispatches().stream().filter(t -> t.toolName().equals(requirement.getKey())
                        && meaningful(t, task.answerable())).count();
                if (Math.min(audited, returned) < requirement.getValue()) reasons.add("REQUIRED_TOOL_NOT_SATISFIED:" + requirement.getKey());
            }
        }
        return reasons;
    }

    private boolean meaningful(Dispatch dispatch, boolean answerable) {
        var summary = dispatch.resultSummary();
        if (!"OK".equals(summary.get("status"))) return false;
        if (dispatch.toolName().equals("getDocumentMetadata")) return "true".equals(summary.get("found"));
        if (answerable && (dispatch.toolName().equals("searchKnowledgeBase") || dispatch.toolName().equals("getChunkContext"))) {
            try { return Integer.parseInt(summary.getOrDefault("evidenceChunks", "0")) > 0; }
            catch (NumberFormatException e) { return false; }
        }
        return true;
    }

    static boolean successful(AgentStepSummary.Status status) {
        return status == AgentStepSummary.Status.COMPLETED || status == AgentStepSummary.Status.SUCCEEDED;
    }
    static long replans(Turn turn) {
        return Math.max(0L, turn.steps().stream().filter(s -> s.kind() == AgentStepSummary.Kind.PLAN
                && s.operation().equals("validated_plan") && successful(s.status())).count() - 1);
    }

    private void citationChecks(long kb, Turn turn, List<String> reasons) {
        Set<String> tokens = new HashSet<>();
        String answer = turn.result().answer();
        if (answer == null) return;
        var matches = TOKEN.matcher(answer);
        while (matches.find()) tokens.add(matches.group().substring(1, matches.group().length() - 1));
        var shaped = SHAPED.matcher(answer);
        while (shaped.find()) if (!TOKEN.matcher(shaped.group()).matches()) reasons.add("MALFORMED_CITATION:T" + turn.turn());
        Map<String, AgentCitation> used = new HashMap<>();
        for (AgentCitation citation : turn.result().citations()) {
            if (citation.knowledgeBaseId() != kb) reasons.add("CROSS_KNOWLEDGE_BASE_CITATION");
            if (used.putIfAbsent(citation.citationId(), citation) != null) reasons.add("DUPLICATE_CITATION");
            if (turn.evidence().stream().noneMatch(e -> e.knowledgeBaseId() == kb && e.citation().equals(toRag(citation)))) reasons.add("CITATION_NOT_IN_THIS_TURN:" + citation.citationId());
        }
        if (!tokens.equals(used.keySet())) reasons.add("ANSWER_CITATION_METADATA_MISMATCH:T" + turn.turn());
        if (turn.evidence().stream().anyMatch(e -> e.knowledgeBaseId() != kb)) reasons.add("CROSS_KNOWLEDGE_BASE_CONTEXT");
    }

    private void validateBinding(String runId, String datasetSha, Task task, Observation observation, Review review) {
        if (!Objects.equals(runId, review.runId()) || !Objects.equals(datasetSha, review.datasetSha256())
                || !Objects.equals(task.caseId(), review.caseId())
                || !Objects.equals(resultHash(runId, datasetSha, task, observation), review.resultSha256())
                || review.reviewer() == null || review.reviewer().isBlank() || review.reviewedAt() == null
                || review.referenceVerified() == null || review.allTurnsAcceptable() == null || review.safeErrorHandling() == null) {
            throw new IllegalArgumentException("Unbound or incomplete task review: " + task.caseId());
        }
        Instant.parse(review.reviewedAt());
        Set<String> facts = new HashSet<>();
        for (FactReview fact : review.facts()) {
            if (fact.covered() == null || !facts.add(fact.factId()) || (!fact.covered() && !fact.citationIds().isEmpty())) throw new IllegalArgumentException("Incomplete fact review");
        }
        if (!facts.equals(task.requiredFacts().stream().map(f -> f.id()).collect(java.util.stream.Collectors.toSet()))
                || (task.answerable() ? review.criticalConflictFree() == null || review.coreClaimsSupported() == null : review.refusalCorrect() == null)) {
            throw new IllegalArgumentException("Review must assess every required fact and all gates");
        }
    }

    private RagAccuracyEvaluator.Observation answerObservation(Turn turn) {
        return new RagAccuracyEvaluator.Observation(true, turn.result().answer(), turn.result().citations().stream()
                .map(AgentBenchmarkEvaluator::toRag).toList(), turn.evidence(), null, turn.result().modelName(),
                turn.result().usage().modelCalls() == null ? null : turn.result().usage().modelCalls() > 0, false, turn.elapsedMillis(), null);
    }
    static RagCitationResponse toRag(AgentCitation c) {
        return new RagCitationResponse(c.citationId(), c.documentId(), c.documentName(), c.chunkId(), c.pageFrom(), c.pageTo(), c.quote());
    }
    private List<String> distinct(List<String> reasons) { return reasons.stream().distinct().toList(); }

    public enum Status { SUCCEEDED, FAILED, PENDING_REVIEW, ERROR, NOT_RUN }
    public record Observation(boolean executed, List<Turn> turns, double elapsedMillis, String errorCode) {
        public Observation {
            turns = List.copyOf(turns);
            if (!Double.isFinite(elapsedMillis) || elapsedMillis < 0 || (!executed && (!turns.isEmpty() || errorCode == null))) throw new IllegalArgumentException("Invalid observation");
        }
        static Observation notRun(String reason) { return new Observation(false, List.of(), 0, reason); }
    }
    public record Turn(int turn, String question, String inputMemorySha256, int inputMemoryMessages, Long conversationId,
                       AgentResult result, List<RagEvidenceChunk> evidence, List<AgentStepSummary> steps,
                       List<AgentToolCallSummary> tools, List<Dispatch> dispatches, boolean evidenceVerified,
                       boolean hiddenReasoningDetected, double elapsedMillis, String errorCode) {
        public Turn {
            evidence = List.copyOf(evidence); steps = List.copyOf(steps); tools = List.copyOf(tools); dispatches = List.copyOf(dispatches);
            if (result != null && result.answer() != null) {
                String visible = AgentBenchmarkText.visible(result.answer());
                hiddenReasoningDetected |= !visible.equals(result.answer());
                result = new AgentResult(visible, result.citations(), result.steps(), result.toolCalls(), result.status(),
                        result.usage(), result.failure(), result.modelName());
            }
            if (turn < 1 || question == null || question.isBlank() || inputMemoryMessages < 0
                    || !Double.isFinite(elapsedMillis) || elapsedMillis < 0) throw new IllegalArgumentException("Invalid observed turn");
        }
    }
    public record Review(String runId, String datasetSha256, String caseId, String resultSha256, String reviewer,
                         String reviewedAt, Boolean referenceVerified, List<FactReview> facts,
                         Boolean criticalConflictFree, Boolean coreClaimsSupported, Boolean refusalCorrect,
                         Boolean allTurnsAcceptable, Boolean safeErrorHandling, String notes) {
        public Review { facts = facts == null ? List.of() : List.copyOf(facts); }
        boolean untouched() {
            return (reviewer == null || reviewer.isBlank()) && reviewedAt == null && referenceVerified == null
                    && criticalConflictFree == null && coreClaimsSupported == null && refusalCorrect == null
                    && allTurnsAcceptable == null && safeErrorHandling == null && facts.stream().allMatch(f -> f.covered() == null);
        }
    }
    public record Evaluation(Status status, Double reviewedFactCoverage, List<String> failureReasons, Review humanReview) {
        public Evaluation { failureReasons = List.copyOf(failureReasons); }
    }
}
