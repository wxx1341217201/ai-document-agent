package com.wxx.aidocumentagent.agent.benchmark;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentBenchmarkReportTest {
    @Test void everyFailureRemainsInOverallAndPerModeDenominators() {
        var evaluator = new AgentBenchmarkEvaluator(new JsonMapper());
        var task = task(AgentMode.RETRIEVAL_FIRST); var obs = observation(task);
        var reviewed = review(evaluator, task, obs);
        var success = new AgentBenchmarkReport.CaseResult(task, obs, reviewed.resultSha256(), evaluator.evaluate("run", "d".repeat(64), task, obs, reviewed));
        var other = task(AgentMode.REACT); var failedObs = new AgentBenchmarkEvaluator.Observation(true, List.of(), 1, "MODEL_UNAVAILABLE");
        var failedReview = review(evaluator, other, failedObs);
        var failed = new AgentBenchmarkReport.CaseResult(other, failedObs, failedReview.resultSha256(), evaluator.evaluate("run", "d".repeat(64), other, failedObs, failedReview));
        var report = AgentBenchmarkReport.build("run", "2026-10-02T00:00:00Z", new AgentBenchmarkReport.DatasetInfo("unit-fixture", "d".repeat(64), 2, 2, "unit_test_fixture", "f".repeat(64)),
                Map.of("mode", "TEST", "corpusStable", true), List.of("Unit fixtures only."), List.of(success, failed));
        assertThat(report.overall().taskSuccessRate()).isEqualTo(0.5);
        assertThat(report.byMode().get(AgentMode.REACT).taskSuccessRate()).isEqualTo(0.0);
        assertThat(report.byMode().get(AgentMode.RETRIEVAL_FIRST).taskSuccessRate()).isEqualTo(1.0);
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(report.failureDetails()).hasSize(1);
    }

    @Test void eightyOfOneHundredMeetsTargetWhileWeakModeRemainsVisible() {
        var report = fullReport(80);
        assertThat(report.overall().taskSuccessRate()).isEqualTo(0.80);
        assertThat(report.targetStatus()).isEqualTo("MET");
        assertThat(report.targetByMode().get(AgentMode.PLAN_EXECUTE_REFLECT)).isEqualTo("NOT_MET");
        assertThat(report.byMode().get(AgentMode.PLAN_EXECUTE_REFLECT).succeeded()).isEqualTo(15);
        assertThat(report.byMode().get(AgentMode.PLAN_EXECUTE_REFLECT).taskSuccessRate()).isEqualTo(15.0 / 35);
        assertThat(fullReport(79).targetStatus()).isEqualTo("NOT_MET");
    }

    @Test void pendingReviewHasSeparateAutomaticRateAndCannotClaimTarget() {
        var mapper = new JsonMapper(); var evaluator = new AgentBenchmarkEvaluator(mapper);
        var task = task(AgentMode.REACT); var obs = observation(task);
        var pending = new AgentBenchmarkReport.CaseResult(task, obs, evaluator.resultHash("run", "d".repeat(64), task, obs),
                evaluator.evaluate("run", "d".repeat(64), task, obs, null));
        var report = AgentBenchmarkReport.build("run", "2026-10-13T00:00:00Z",
                new AgentBenchmarkReport.DatasetInfo("unit-fixture", "d".repeat(64), 2, 1, "unit_test_fixture", "f".repeat(64)),
                Map.of("mode", "LIVE", "corpusStable", true), List.of("Fixture only."), List.of(pending));
        assertThat(report.overall().automaticGatePassRate()).isEqualTo(1.0);
        assertThat(report.overall().successRateLowerBound()).isEqualTo(0.0);
        assertThat(report.overall().successRateUpperBound()).isEqualTo(1.0);
        assertThat(report.overall().taskSuccessRate()).isNull();
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(report.targetByMode().values()).containsOnly("NOT_EVALUABLE");
    }

    private AgentBenchmarkReport fullReport(int successes) {
        var mapper = new JsonMapper(); var evaluator = new AgentBenchmarkEvaluator(mapper);
        var cases = new java.util.ArrayList<AgentBenchmarkReport.CaseResult>();
        for (int i = 0; i < 100; i++) {
            var mode = i < 30 ? AgentMode.RETRIEVAL_FIRST : i < 65 ? AgentMode.REACT : AgentMode.PLAN_EXECUTE_REFLECT;
            var node = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(task(mode));
            node.put("caseId", "UNIT-" + i);
            var task = mapper.treeToValue(node, AgentBenchmarkDataset.Task.class);
            var obs = i < successes ? observation(task) : new AgentBenchmarkEvaluator.Observation(true, List.of(), 1, "MODEL_UNAVAILABLE");
            var review = review(evaluator, task, obs);
            cases.add(new AgentBenchmarkReport.CaseResult(task, obs, review.resultSha256(), evaluator.evaluate("run", "d".repeat(64), task, obs, review)));
        }
        return AgentBenchmarkReport.build("run", "2026-10-13T00:00:00Z",
                new AgentBenchmarkReport.DatasetInfo("unit-fixture", "d".repeat(64), 2, 100, "unit_test_fixture", "f".repeat(64)),
                Map.of("mode", "LIVE", "corpusStable", true), List.of("Unit fixtures only."), cases);
    }
}
