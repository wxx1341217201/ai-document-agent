package com.wxx.aidocumentagent.recovery.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkReport.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkEvaluator.*;

class RecoveryBenchmarkReportTest {
    private final JsonMapper mapper = new JsonMapper();
    private final RecoveryBenchmarkPlan plan = RecoveryBenchmarkPlan.generate(1000, 42);
    private List<Case> cases(int losses) {
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < plan.samples().size(); i++) cases.add(new Case(plan.samples().get(i), true, true, (double) i + 1,
                RecoveryBenchmarkEvaluatorTest.snapshot(), RecoveryBenchmarkEvaluatorTest.snapshot(), null, null,
                Map.of(), new Evaluation(i < losses ? Outcome.SESSION_LOST : Outcome.RECOVERED, List.of())));
        return cases;
    }
    private RecoveryBenchmarkReport report(List<Case> cases) {
        return build("c69f70e8-dc24-4422-8b3e-25e9f871333c", plan, Map.of("profile", "test_only", "mode", "LIVE", "realStorageVerified", true), List.of(), cases, mapper);
    }

    @Test void fourLossesOf1000StrictlyMeetTarget() {
        var report = report(cases(4));
        assertThat(report.overall().sessionLossRate()).isEqualTo(.4);
        assertThat(report.overall().recoverySuccessRate()).isEqualTo(99.6);
        assertThat(report.targetStatus()).isEqualTo("MET");
        assertThat(report.overall().p95Millis()).isEqualTo(950.0);
    }
    @Test void fiveLossesOf1000DoNotMeetStrictTarget() {
        var report = report(cases(5));
        assertThat(report.overall().sessionLossRate()).isEqualTo(.5);
        assertThat(report.targetStatus()).isEqualTo("NOT_MET");
    }
    @Test void recoveryErrorRetainsDenominatorAndPreventsFalseZeroLossClaim() {
        var cases = cases(0);
        cases.set(0, new Case(plan.samples().getFirst(), true, true, 90000.0, RecoveryBenchmarkEvaluatorTest.snapshot(), null,
                null, null, Map.of(), new Evaluation(Outcome.RECOVERY_FAILED, List.of("RECOVERY_POLL_TIMEOUT"))));
        var report = report(cases);
        assertThat(report.overall().planned()).isEqualTo(1000);
        assertThat(report.overall().attempted()).isEqualTo(1000);
        assertThat(report.overall().sessionLossRate()).isNull();
        assertThat(report.overall().recoverySuccessRate()).isEqualTo(99.9);
        assertThat(report.overall().observedLossLowerBound()).isZero();
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
    }
    @Test void validationOnlyHasUnavailableMetrics() {
        List<Case> cases = plan.samples().stream().map(s -> new Case(s, false, false, null, null, null, null, null,
                Map.of(), new Evaluation(Outcome.NOT_RUN, List.of("VALIDATE_ONLY")))).toList();
        var report = report(cases);
        assertThat(report.overall().sessionLossRate()).isNull();
        assertThat(report.overall().recoverySuccessRate()).isNull();
        assertThat(report.overall().p95Millis()).isNull();
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(report.byFault().values()).allMatch(s -> s.notRun() > 0);
    }
    @Test void missingOrDuplicatedSampleCannotChangeDenominator() {
        var cases = cases(0); cases.removeLast();
        assertThatThrownBy(() -> report(cases)).hasMessage("RECOVERY_DENOMINATOR_CHANGED");
        var duplicate = cases(0); duplicate.set(1, duplicate.getFirst());
        assertThatThrownBy(() -> report(duplicate)).hasMessage("RECOVERY_PLAN_CHANGED");
    }
    @Test void syntheticCasesWithoutVerifiedStorageCannotClaimLiveTarget() {
        assertThat(build("run", plan, Map.of("mode", "LIVE", "realStorageVerified", false), List.of(), cases(0), mapper)
                .targetStatus()).isEqualTo("NOT_EVALUABLE");
    }
    @Test void generatedReportsRoundTripAndDoNotOverwrite(@TempDir Path directory) throws Exception {
        var report = report(cases(4));
        Path json = report.write(directory, mapper);
        assertThat(mapper.readValue(Files.readString(json), RecoveryBenchmarkReport.class)).isEqualTo(report);
        assertThat(Files.readString(Path.of(json.toString().replace(".json", ".md")))).contains("REDIS_RESTART", "0.4000%", "Per sample observations");
    }
    @Test void invalidTimingOrUnverifiedSuccessIsRejected() {
        assertThatThrownBy(() -> new Case(plan.samples().getFirst(), true, true, Double.NaN, RecoveryBenchmarkEvaluatorTest.snapshot(),
                null, null, null, Map.of(), new Evaluation(Outcome.RECOVERY_FAILED, List.of()))).hasMessage("INVALID_RECOVERY_OBSERVATION");
        assertThatThrownBy(() -> new Case(plan.samples().getFirst(), true, false, 1.0, RecoveryBenchmarkEvaluatorTest.snapshot(),
                RecoveryBenchmarkEvaluatorTest.snapshot(), null, null, Map.of(), new Evaluation(Outcome.RECOVERED, List.of()))).hasMessage("INVALID_RECOVERY_OBSERVATION");
    }
}
