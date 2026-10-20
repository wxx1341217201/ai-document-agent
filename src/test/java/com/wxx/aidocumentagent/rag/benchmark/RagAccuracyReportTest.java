package com.wxx.aidocumentagent.rag.benchmark;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Evaluation;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Observation;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagAccuracyReportTest {

    @Test
    void refusalSuccessNeverIncreasesOrdinaryQaAccuracy() {
        var correct = result("S1", true, Status.CORRECT, true);
        var wrong = result("S2", true, Status.INCORRECT, true);
        var refusal = result("N1", false, Status.REFUSAL_CORRECT, true);
        var report = report(List.of(correct, wrong, refusal));
        assertThat(report.overall().qaAccuracy()).isEqualTo(0.5);
        assertThat(report.overall().refusalAccuracy()).isEqualTo(1.0);
        assertThat(report.overall().answerable()).isEqualTo(2);
        assertThat(report.targetStatus()).isEqualTo("NOT_MET");
    }

    @Test
    void partialHumanReviewAndMechanicalFailuresCannotBeClaimedAsFinalAccuracy() {
        var report = report(List.of(result("S1", true, Status.CORRECT, true),
                result("S2", true, Status.INCORRECT, false)));
        assertThat(report.overall().qaAccuracy()).isNull();
        assertThat(report.overall().pendingReview()).isEqualTo(1);
        assertThat(report.overall().qaAccuracyUpperBound()).isEqualTo(0.5);
        assertThat(report.status()).isEqualTo("NEEDS_HUMAN_REVIEW");
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
    }

    @Test
    void runtimeErrorsRemainInDenominatorAndInvalidateTargetClaim() {
        var report = report(List.of(result("S1", true, Status.CORRECT, true), result("S2", true, Status.ERROR, false)));
        assertThat(report.overall().qaAccuracy()).isEqualTo(0.5);
        assertThat(report.overall().errors()).isEqualTo(1);
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
    }

    @Test
    void thresholdUsesUnroundedAccuracyAndSeparateRefusalDenominator() {
        var cases = new java.util.ArrayList<RagAccuracyReport.CaseResult>();
        for (int index = 0; index < 200; index++) {
            cases.add(result("S" + index, true, index < 166 ? Status.CORRECT : Status.INCORRECT, true));
        }
        cases.add(result("N1", false, Status.REFUSAL_INCORRECT, true));
        assertThat(report(cases).overall().qaAccuracy()).isEqualTo(0.83);
        assertThat(report(cases).targetStatus()).isEqualTo("MET");
        cases.set(165, result("S165", true, Status.INCORRECT, true));
        assertThat(report(cases).overall().qaAccuracy()).isEqualTo(0.825);
        assertThat(report(cases).targetStatus()).isEqualTo("NOT_MET");
    }

    private RagAccuracyReport.CaseResult result(String id, boolean answerable, Status status, boolean reviewed) {
        var sample = answerable ? RagAccuracyDatasetTest.sample(id, 2)
                : new RagAccuracyDataset.Sample(id, 2, "email?", RagAccuracyDataset.QuestionType.UNANSWERABLE,
                false, "我不知道", List.of(), 1.0, List.of(), List.of(), "draft");
        var observation = new Observation(true, "unit test answer", List.of(), List.of(), null, "fake-test-model",
                true, false, 1, status == Status.ERROR ? "TEST_ERROR" : null);
        var review = reviewed ? new RagAccuracyEvaluator.Review("test-run", "test-dataset", id, "test-output",
                "unit-test-reviewer", "2026-10-02T12:00:00Z", true, List.of(), true, true, true, "synthetic unit test") : null;
        var evaluation = new Evaluation(status, null, null, List.of(), List.of(), review);
        return new RagAccuracyReport.CaseResult(sample, observation, "test-output", evaluation);
    }

    private RagAccuracyReport report(List<RagAccuracyReport.CaseResult> cases) {
        return RagAccuracyReport.build("test-run", "2026-10-02T12:00:00Z",
                new RagAccuracyReport.DatasetInfo("test-fixture", "test-dataset", 2, cases.size(), "test-draft", null),
                Map.of("mode", "LIVE"), List.of("synthetic unit test only"), cases);
    }
}
