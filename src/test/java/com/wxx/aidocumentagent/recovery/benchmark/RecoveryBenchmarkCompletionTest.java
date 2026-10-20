package com.wxx.aidocumentagent.recovery.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkEvaluator.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkReport.*;

class RecoveryBenchmarkCompletionTest {
    private final JsonMapper mapper = new JsonMapper();
    private final RecoveryBenchmarkPlan plan = RecoveryBenchmarkPlan.generate(1000, 42);
    private Case recovered(RecoveryBenchmarkPlan.Sample sample) {
        return new Case(sample, true, true, 7.0, RecoveryBenchmarkEvaluatorTest.snapshot(), RecoveryBenchmarkEvaluatorTest.snapshot(),
                null, null, Map.of(), new Evaluation(Outcome.RECOVERED, List.of()));
    }
    private Case notRun(RecoveryBenchmarkPlan.Sample sample, String reason) {
        return new Case(sample, false, false, null, null, null, null, null, Map.of(), new Evaluation(Outcome.NOT_RUN, List.of(reason)));
    }
    private List<Case> cases() {
        var cases = new ArrayList<>(plan.samples().stream().map(this::recovered).toList());
        cases.set(0, notRun(plan.samples().getFirst(), "HttpTimeoutException"));
        return cases;
    }
    private RecoveryBenchmarkReport report(List<Case> cases) {
        return build("source", plan, Map.of("mode", "LIVE", "profile", "CONTROLLED_AGENT_REAL_STORAGE_PROCESS_FAULTS", "realStorageVerified", true),
                List.of(), cases, mapper);
    }

    @Test void preparationTimeoutIsRecordedAndOnlyOneRealRecoveryAttemptIsCounted() throws Exception {
        var sample = plan.samples().getFirst(); var timeout = notRun(sample, "HttpTimeoutException");
        AtomicInteger preparations = new AtomicInteger();
        var result = RecoveryBenchmarkRunner.executeWithPreparationRetries(sample,
                ignored -> preparations.incrementAndGet() == 1 ? timeout : recovered(sample));
        assertThat(preparations.get()).isEqualTo(2);
        assertThat(result.attempted()).isTrue();
        assertThat(result.recoveryMillis()).isEqualTo(7.0);
        assertThat(result.faultEvidence().get("preparationAttempts")).isEqualTo(2);
        assertThat(result.faultEvidence().get("preparationFailures")).isEqualTo(List.of(timeout));
    }

    @Test void preparationRetriesStopAfterThreeWithoutInventingAnAttempt() throws Exception {
        AtomicInteger preparations = new AtomicInteger(); var sample = plan.samples().getFirst();
        var result = RecoveryBenchmarkRunner.executeWithPreparationRetries(sample, ignored -> {
            preparations.incrementAndGet(); return notRun(sample, "ConnectException");
        });
        assertThat(preparations.get()).isEqualTo(3);
        assertThat(result.attempted()).isFalse();
        assertThat(result.evaluation().outcome()).isEqualTo(Outcome.NOT_RUN);
    }

    @Test void injectedFailureIsNeverRetriedOrRemovedFromDenominator() throws Exception {
        var failure = new Case(plan.samples().getFirst(), true, true, 90000.0, RecoveryBenchmarkEvaluatorTest.snapshot(), null,
                null, null, Map.of(), new Evaluation(Outcome.RECOVERY_FAILED, List.of("HttpTimeoutException")));
        AtomicInteger calls = new AtomicInteger();
        assertThat(RecoveryBenchmarkRunner.executeWithPreparationRetries(failure.sample(), ignored -> { calls.incrementAndGet(); return failure; }))
                .isSameAs(failure);
        assertThat(calls.get()).isEqualTo(1);
        var cases = cases(); cases.set(1, failureWithSample(failure, plan.samples().get(1)));
        assertThatThrownBy(() -> RecoveryBenchmarkRunner.completionSamples(report(cases), plan, mapper))
                .hasMessage("COMPLETION_CANNOT_REPLACE_RECOVERY_ATTEMPTS");
    }

    @Test void badRequestPreparationIsNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger(); var sample = plan.samples().getFirst();
        var rejected = notRun(sample, "HTTP_401");
        assertThat(RecoveryBenchmarkRunner.executeWithPreparationRetries(sample, ignored -> { calls.incrementAndGet(); return rejected; }))
                .isSameAs(rejected);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void completionKeepsOriginalAttemptsAndConfirmedLosses() {
        var original = cases(); var prior = original.get(1);
        original.set(1, new Case(prior.sample(), true, true, prior.recoveryMillis(), prior.before(), prior.after(), null, null,
                Map.of(), new Evaluation(Outcome.SESSION_LOST, List.of("PERSISTED_MESSAGE_MISSING"))));
        var source = report(original);
        assertThat(RecoveryBenchmarkRunner.completionSamples(source, plan, mapper)).containsExactly(plan.samples().getFirst());
        var merged = RecoveryBenchmarkRunner.mergeCompletion(original, List.of(recovered(plan.samples().getFirst())));
        var completed = report(merged);
        assertThat(completed.overall().attempted()).isEqualTo(1000);
        assertThat(completed.overall().sessionLost()).isEqualTo(1);
        assertThat(completed.overall().sessionLossRate()).isEqualTo(.1);
        assertThat(completed.overall().recovered()).isEqualTo(999);
        assertThat(merged.get(1)).isSameAs(original.get(1));
        assertThat(source.overall().notRun()).isEqualTo(1);
    }

    @Test void completionCannotReplaceExistingAttemptsOrChangeFrozenSample() {
        var original = cases();
        assertThatThrownBy(() -> RecoveryBenchmarkRunner.mergeCompletion(original, List.of(recovered(plan.samples().getFirst()), recovered(plan.samples().get(1)))))
                .hasMessage("COMPLETION_CANNOT_REPLACE_RECOVERY_ATTEMPTS");
        var changed = new RecoveryBenchmarkPlan.Sample(plan.samples().getFirst().caseId(), plan.samples().getFirst().fault(), 99);
        assertThatThrownBy(() -> RecoveryBenchmarkRunner.mergeCompletion(original, List.of(recovered(changed)))).hasMessage("COMPLETION_PLAN_CHANGED");
        assertThatThrownBy(() -> RecoveryBenchmarkRunner.completionSamples(report(original), RecoveryBenchmarkPlan.generate(1000, 43), mapper))
                .hasMessage("COMPLETION_PLAN_HASH_MISMATCH");
    }

    @Test void componentOrConfigurationChangesPreventCombiningIncompatibleEvidence() {
        String runner = RecoveryBenchmarkRunner.class.getName(), checker = RecoveryBenchmarkEvaluator.class.getName();
        var code = Map.of(runner, "new-orchestrator", checker, "same-checker");
        var configuration = Map.of("profile", "same-profile");
        var source = Map.<String, Object>of("codeSha256", Map.of(runner, "old-orchestrator", checker, "same-checker"), "configurationSha256", configuration);
        assertThatCode(() -> RecoveryBenchmarkRunner.verifyCompletionFingerprints(source, code, configuration)).doesNotThrowAnyException();
        assertThatThrownBy(() -> RecoveryBenchmarkRunner.verifyCompletionFingerprints(source, Map.of(runner, "new-orchestrator", checker, "changed"), configuration))
                .hasMessage("COMPLETION_COMPONENT_CHANGED:" + checker);
        assertThatThrownBy(() -> RecoveryBenchmarkRunner.verifyCompletionFingerprints(source, code, Map.of("profile", "changed-profile")))
                .hasMessage("COMPLETION_CONFIGURATION_CHANGED");
    }

    private Case failureWithSample(Case failure, RecoveryBenchmarkPlan.Sample sample) {
        return new Case(sample, failure.attempted(), failure.faultVerified(), failure.recoveryMillis(), failure.before(), failure.after(),
                null, null, failure.faultEvidence(), failure.evaluation());
    }
}
