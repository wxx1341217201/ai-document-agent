package com.wxx.aidocumentagent.recovery.benchmark;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkEvaluator.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkObservation.*;

class RecoveryBenchmarkEvaluatorTest {
    private final RecoveryBenchmarkEvaluator evaluator = new RecoveryBenchmarkEvaluator();
    static Snapshot snapshot() {
        return new Snapshot(42, 7, 2, List.of(new Message(1, 1, USER, "user-sha", "citations-sha"),
                new Message(2, 2, ASSISTANT, "answer-sha", "citations-sha")), List.of(
                new Memory(1, USER, "user-sha"), new Memory(2, ASSISTANT, "answer-sha")), true);
    }
    private Evaluation check(Snapshot after) { return evaluator.evaluate(snapshot(), after, true, null, null, null, true); }
    private Execution execution(String status, String answer, String result, Map<String, String> steps) {
        return new Execution("id", 7, 42L, status, answer, result, steps, 1, null);
    }

    @Test void durableMessagesAndBoundedMemoryRestoredIsSuccessful() {
        assertThat(check(snapshot()).outcome()).isEqualTo(Outcome.RECOVERED);
    }
    @Test void missingPersistedUserIsLoss() {
        Snapshot before = snapshot();
        assertThat(check(new Snapshot(42, 7, 2, List.of(before.messages().getLast()), before.memory(), true)).outcome()).isEqualTo(Outcome.SESSION_LOST);
    }
    @Test void missingCompletedAssistantIsLoss() {
        Snapshot before = snapshot();
        assertThat(check(new Snapshot(42, 7, 2, List.of(before.messages().getFirst()), before.memory(), true)).outcome()).isEqualTo(Outcome.SESSION_LOST);
    }
    @Test void reorderedMessagesIsLossEvenWhenBothTextsSurvive() {
        Snapshot before = snapshot();
        assertThat(check(new Snapshot(42, 7, 2, before.messages().reversed(), before.memory(), true)).outcome()).isEqualTo(Outcome.SESSION_LOST);
    }
    @Test void knowledgeBaseChangeIsLoss() {
        Snapshot before = snapshot();
        assertThat(check(new Snapshot(42, 8, 2, before.messages(), before.memory(), true)).reasons()).contains("CONVERSATION_KNOWLEDGE_BASE_MISMATCH");
    }
    @Test void changedCitationOrPersistedIdIsLoss() {
        Snapshot before = snapshot();
        assertThat(check(new Snapshot(42, 7, 2, List.of(before.messages().getFirst(),
                new Message(2, 2, ASSISTANT, "answer-sha", "changed-citations")), before.memory(), true)).outcome()).isEqualTo(Outcome.SESSION_LOST);
    }
    @Test void durableHistoryLongerThanCacheIsNotLoss() {
        Snapshot before = snapshot();
        var trimmed = new Snapshot(42, 7, 2, before.messages(), List.of(before.memory().getLast()), true);
        assertThat(evaluator.evaluate(trimmed, trimmed, true, null, null, null, true).outcome()).isEqualTo(Outcome.RECOVERED);
    }
    @Test void cacheRepopulationFailureWithIntactHistoryIsRecoveryFailure() {
        Snapshot before = snapshot();
        var after = new Snapshot(42, 7, 2, before.messages(), before.memory(), false);
        assertThat(check(after).outcome()).isEqualTo(Outcome.RECOVERY_FAILED);
        assertThat(check(after).reasons()).contains("CACHE_REPOPULATION_NOT_VERIFIED");
    }
    @Test void unavailableAuthorityCannotBeClaimedAsLossOrSuccess() {
        assertThat(check(null).outcome()).isEqualTo(Outcome.RECOVERY_FAILED);
    }
    @Test void unchangedStateWithoutVerifiedFaultCannotClaimRecovery() {
        assertThat(evaluator.evaluate(snapshot(), snapshot(), false, null, null, null, true).outcome()).isEqualTo(Outcome.RECOVERY_FAILED);
    }
    @Test void resultCheckpointMissingIsLossEvenIfConversationSurvives() {
        var before = execution("RUNNING", "expected", "durable-result", Map.of("fixture_read", "SUCCEEDED"));
        var after = execution("FAILED", null, null, Map.of("fixture_read", "SUCCEEDED"));
        assertThat(evaluator.evaluate(snapshot(), snapshot(), true, before, after, "expected", true).reasons())
                .contains("COMMITTED_RESULT_CHECKPOINT_CHANGED_OR_MISSING");
    }
    @Test void replayProducingInconsistentAnswerIsLoss() {
        var before = execution("RUNNING", null, null, Map.of("fixture_read", "SUCCEEDED"));
        var after = execution("SUCCEEDED", "different", "result", Map.of("fixture_read", "SUCCEEDED"));
        assertThat(evaluator.evaluate(snapshot(), snapshot(), true, before, after, "expected", true).reasons()).contains("REPLAY_RESULT_INCONSISTENT");
    }
    @Test void replayProducingSameAnswerWithPreservedCheckpointIsSuccess() {
        var before = execution("RUNNING", null, null, Map.of("fixture_read", "SUCCEEDED"));
        var after = execution("SUCCEEDED", "expected", "result", Map.of("fixture_read", "SUCCEEDED"));
        assertThat(evaluator.evaluate(snapshot(), snapshot(), true, before, after, "expected", true).outcome()).isEqualTo(Outcome.RECOVERED);
    }
    @Test void duplicateDeliveryWithSameResultStillFailsIdempotencyButIsNotInventedSessionLoss() {
        var before = execution("RUNNING", null, null, Map.of("fixture_read", "SUCCEEDED"));
        var after = execution("SUCCEEDED", "expected", "result", Map.of("fixture_read", "SUCCEEDED"));
        Evaluation result = evaluator.evaluate(snapshot(), snapshot(), true, before, after, "expected", false);
        assertThat(result.reasons()).contains("REDELIVERY_CREATED_ANOTHER_EXECUTION");
        assertThat(result.outcome()).isEqualTo(Outcome.RECOVERY_FAILED);
    }
    @Test void preservedCheckpointWithoutTerminalSuccessIsRecoveryFailure() {
        var before = execution("RUNNING", null, null, Map.of("fixture_read", "SUCCEEDED"));
        var after = execution("PAUSED", null, null, Map.of("fixture_read", "SUCCEEDED"));
        assertThat(evaluator.evaluate(snapshot(), snapshot(), true, before, after, "expected", true).outcome()).isEqualTo(Outcome.RECOVERY_FAILED);
    }
    @Test void missingIntermediateStepWithConsistentReplayFailsCheckpointCheckWithoutInflatingStrictLoss() {
        var before = execution("RUNNING", null, null, Map.of("fixture_read", "SUCCEEDED"));
        var after = execution("SUCCEEDED", "expected", "result", Map.of());
        Evaluation result = evaluator.evaluate(snapshot(), snapshot(), true, before, after, "expected", true);
        assertThat(result.outcome()).isEqualTo(Outcome.RECOVERY_FAILED);
        assertThat(result.reasons()).contains("COMMITTED_STEP_CHECKPOINT_MISSING");
    }
    @Test void finalizationMustNotLeaveCompletedAgentStepRunning() {
        var before = execution("RUNNING", "expected", "result", Map.of("fixture_read", "SUCCEEDED", "agent_run", "SUCCEEDED"));
        var after = execution("SUCCEEDED", "expected", "result", Map.of("fixture_read", "SUCCEEDED", "agent_run", "RUNNING"));
        Evaluation result = evaluator.evaluate(snapshot(), snapshot(), true, before, after, "expected", true);
        assertThat(result.outcome()).isEqualTo(Outcome.RECOVERY_FAILED);
        assertThat(result.reasons()).contains("COMPLETED_STEP_STATUS_NOT_RECOVERED:agent_run");
    }
}
