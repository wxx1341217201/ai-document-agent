package com.wxx.aidocumentagent.agent.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentFailure;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;
import com.wxx.aidocumentagent.agent.AgentUsage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBenchmarkEvaluatorTest {
    private final JsonMapper mapper = new JsonMapper();
    private final AgentBenchmarkEvaluator evaluator = new AgentBenchmarkEvaluator(mapper);
    private AgentBenchmarkEvaluator.Evaluation evaluate(AgentBenchmarkDataset.Task task, AgentBenchmarkEvaluator.Observation obs) {
        return evaluator.evaluate("run", "d".repeat(64), task, obs, review(evaluator, task, obs));
    }
    @Test void correctLookingAnswerNeedsHumanReviewAndAllGates() {
        var task = task(AgentMode.REACT); var obs = observation(task);
        assertThat(evaluator.evaluate("run", "d".repeat(64), task, obs, null).status()).isEqualTo(AgentBenchmarkEvaluator.Status.PENDING_REVIEW);
        assertThat(evaluate(task, obs).status()).isEqualTo(AgentBenchmarkEvaluator.Status.SUCCEEDED);
        var good = review(evaluator, task, obs);
        var rejected = new AgentBenchmarkEvaluator.Review(good.runId(), good.datasetSha256(), good.caseId(), good.resultSha256(),
                good.reviewer(), good.reviewedAt(), true, good.facts(), true, true, true, false, false, "Incorrect prior turn.");
        assertThat(evaluator.evaluate("run", "d".repeat(64), task, obs, rejected).failureReasons())
                .contains("INTERMEDIATE_TURNS_NOT_ACCEPTABLE", "UNSAFE_ERROR_HANDLING");
    }
    @Test void aliasesDoNotOverrideMissingEvidenceForeignCitationsOrFailedState() {
        var task = task(AgentMode.REACT); var obs = observation(task); var turn = obs.turns().getFirst();
        AgentCitation foreign = new AgentCitation(99, "C1", 20, "unit-fixture.txt", 250, null, null, TEXT);
        var invalid = new AgentResult("Lewis Carroll [C1]", List.of(foreign), List.of(), turn.tools(), AgentResult.Status.COMPLETED,
                new AgentUsage(null, null, null, 1, 1), null);
        var replaced = replace(obs, invalid, turn.tools(), turn.steps(), turn.dispatches());
        assertThat(evaluate(task, replaced).status()).isEqualTo(AgentBenchmarkEvaluator.Status.FAILED);
        assertThat(evaluate(task, replaced).failureReasons()).contains("CROSS_KNOWLEDGE_BASE_CITATION");
        var failed = new AgentResult(null, List.of(), List.of(), turn.tools(), AgentResult.Status.FAILED,
                new AgentUsage(null, null, null, 1, 1), new AgentFailure(AgentErrorCode.STEP_LIMIT_EXCEEDED));
        assertThat(evaluate(task, replace(obs, failed, turn.tools(), List.of(), turn.dispatches())).failureReasons()).contains("TASK_STATE_MISMATCH:T1");
    }
    @Test void completedToolWithEmptyResultCannotSatisfyRequiredSearch() {
        var task = task(AgentMode.REACT); var obs = observation(task); var t = obs.turns().getFirst();
        var empty = new AgentBenchmarkTrace.Dispatch(2, "searchKnowledgeBase", 1, ToolAction.RUN, Map.of(), Map.of("status", "OK", "evidenceChunks", "0"));
        assertThat(evaluate(task, replace(obs, t.result(), t.tools(), t.steps(), List.of(empty))).failureReasons())
                .contains("REQUIRED_TOOL_NOT_SATISFIED:searchKnowledgeBase");
    }
    @Test void faultAndExactlyOneReplanMustActuallyHappen() {
        var task = task(AgentMode.PLAN_EXECUTE_REFLECT, new AgentBenchmarkDataset.Fault(1, "searchKnowledgeBase", 1, ToolAction.EMPTY_EVIDENCE), true);
        var obs = observation(task); var t = obs.turns().getFirst();
        assertThat(evaluate(task, obs).failureReasons()).contains("REQUIRED_FAULT_NOT_EXERCISED_ONCE", "REQUIRED_REPLAN_NOT_EXERCISED");
        var steps = new ArrayList<>(List.of(plan("r0_plan"), new AgentStepSummary("r0_reflect", AgentStepSummary.Kind.REFLECTION,
                "coverage_gap", AgentStepSummary.Status.SUCCEEDED, null), plan("r1_plan")));
        var calls = List.of(new AgentBenchmarkTrace.Dispatch(2, "searchKnowledgeBase", 1, ToolAction.EMPTY_EVIDENCE, Map.of(), Map.of("status", "OK", "evidenceChunks", "0")),
                new AgentBenchmarkTrace.Dispatch(2, "searchKnowledgeBase", 2, ToolAction.RUN, Map.of(), Map.of("status", "OK", "evidenceChunks", "1")));
        var tools = List.of(t.tools().getFirst(), t.tools().getFirst());
        var result = new AgentResult(t.result().answer(), t.result().citations(), steps, tools, AgentResult.Status.COMPLETED,
                new AgentUsage(null, null, null, 3, 2), null);
        assertThat(evaluate(task, replace(obs, result, tools, steps, calls)).status()).isEqualTo(AgentBenchmarkEvaluator.Status.SUCCEEDED);
        steps.add(plan("r2_plan"));
        assertThat(evaluate(task, replace(obs, result, tools, steps, calls)).failureReasons()).contains("REPLAN_LIMIT_EXCEEDED");
    }
    @Test void toolFailureMustBeAuditedAndRecoveryMustMeetTheAnswerCriteria() {
        var task = task(AgentMode.REACT, new AgentBenchmarkDataset.Fault(1, "searchKnowledgeBase", 1, ToolAction.FAIL), true);
        var obs = observation(task); var t = obs.turns().getFirst();
        var fail = new AgentBenchmarkTrace.Dispatch(2, "searchKnowledgeBase", 1, ToolAction.FAIL, Map.of(), Map.of("status", "TOOL_FAILED"));
        var ok = new AgentBenchmarkTrace.Dispatch(2, "searchKnowledgeBase", 2, ToolAction.RUN, Map.of(), Map.of("status", "OK", "evidenceChunks", "1"));
        var bad = replace(obs, t.result(), t.tools(), List.of(), List.of(fail, ok));
        assertThat(evaluate(task, bad).failureReasons()).contains("FAULT_ERROR_NOT_AUDITED");
        var tools = List.of(new AgentToolCallSummary("searchKnowledgeBase", AgentStepSummary.Status.FAILED, 0L, AgentErrorCode.TOOL_FAILED), t.tools().getFirst());
        var result = new AgentResult(t.result().answer(), t.result().citations(), List.of(), tools, AgentResult.Status.COMPLETED,
                new AgentUsage(null, null, null, 3, 2), null);
        assertThat(evaluate(task, replace(obs, result, tools, List.of(), List.of(fail, ok))).status()).isEqualTo(AgentBenchmarkEvaluator.Status.SUCCEEDED);
    }
    @Test void unknownBudgetsAndHiddenOutputCannotPassOrPersist() throws Exception {
        var task = task(AgentMode.REACT); var obs = observation(task); var t = obs.turns().getFirst();
        var hidden = new AgentResult("<think>PRIVATE_TEST_REASONING</think>Lewis Carroll [C1]", t.result().citations(), List.of(),
                t.tools(), AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);
        var replaced = replace(obs, hidden, t.tools(), List.of(), t.dispatches());
        assertThat(mapper.writeValueAsString(replaced)).doesNotContain("PRIVATE_TEST_REASONING", "<think>");
        assertThat(evaluate(task, replaced).failureReasons()).contains("HIDDEN_REASONING_REJECTED:T1", "UNKNOWN_MODEL_CALL_BUDGET");
        var unclosed = new AgentResult("<think>PRIVATE_TEST_REASONING", List.of(), List.of(), List.of(), AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);
        assertThatThrownBy(() -> replace(obs, unclosed, List.of(), List.of(), List.of())).hasMessage("HIDDEN_REASONING_REJECTED");
    }
    @Test void reviewsBindEntireActualTaskAndCanonicalMapsSurviveRoundTrip() throws Exception {
        var task = task(AgentMode.REACT); var obs = observation(task); var review = review(evaluator, task, obs);
        var changed = new AgentBenchmarkEvaluator.Observation(true, obs.turns(), 300, null);
        assertThatThrownBy(() -> evaluator.evaluate("run", "d".repeat(64), task, changed, review)).hasMessageContaining("Unbound");
        var copy = mapper.readValue(mapper.writeValueAsString(obs), AgentBenchmarkEvaluator.Observation.class);
        assertThat(evaluator.resultHash("run", "d".repeat(64), task, copy)).isEqualTo(review.resultSha256());
    }
    @Test void insufficientEvidenceUsesSeparateExplicitRefusalTask() {
        var task = task(AgentMode.REACT, null, false); var obs = observation(task);
        assertThat(evaluator.evaluate("run", "d".repeat(64), task, obs, null).status()).isEqualTo(AgentBenchmarkEvaluator.Status.PENDING_REVIEW);
        assertThat(evaluate(task, obs).status()).isEqualTo(AgentBenchmarkEvaluator.Status.SUCCEEDED);
    }
    @Test void substitutedConversationMemoryCannotPassDespiteCorrectFinalAnswer() {
        var task = task(AgentMode.REACT); var obs = observation(task); var t = obs.turns().getFirst();
        var wrongMemory = new AgentBenchmarkEvaluator.Turn(1, t.question(), "b".repeat(64), 2, null,
                t.result(), t.evidence(), t.steps(), t.tools(), t.dispatches(), true, false, 2, null);
        var replaced = new AgentBenchmarkEvaluator.Observation(true, List.of(wrongMemory), 2, null);
        assertThat(evaluate(task, replaced).failureReasons()).contains("ACTUAL_TURN_MEMORY_MISMATCH:T1");
    }
    private AgentStepSummary plan(String id) { return new AgentStepSummary(id, AgentStepSummary.Kind.PLAN, "validated_plan", AgentStepSummary.Status.SUCCEEDED, null); }
}
