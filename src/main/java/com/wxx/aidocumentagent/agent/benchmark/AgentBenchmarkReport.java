package com.wxx.aidocumentagent.agent.benchmark;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkDataset.Task;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Evaluation;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Observation;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Status;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Turn;

public record AgentBenchmarkReport(String schemaVersion, String policyVersion, String runId, String createdAt,
                                   String evaluatedAt, DatasetInfo dataset, Map<String, Object> configuration,
                                   List<String> limitations, String status, String targetStatus, Summary overall,
                                   Map<AgentMode, Summary> byMode, Map<AgentMode, String> targetByMode,
                                   Map<String, Summary> byTag, List<CaseResult> cases,
                                   List<FailureDetail> failureDetails, String runIntegritySha256) {
    public static final double TARGET_SUCCESS_RATE = 0.80;

    public AgentBenchmarkReport {
        configuration = Map.copyOf(configuration); limitations = List.copyOf(limitations);
        byMode = Map.copyOf(byMode); targetByMode = targetByMode == null ? Map.of() : Map.copyOf(targetByMode);
        byTag = Map.copyOf(byTag); cases = List.copyOf(cases); failureDetails = List.copyOf(failureDetails);
    }

    public static AgentBenchmarkReport build(String runId, String createdAt, DatasetInfo dataset,
                                               Map<String, Object> configuration, List<String> limitations,
                                               List<CaseResult> cases) {
        Summary overall = summarize(cases);
        Map<AgentMode, Summary> modes = new LinkedHashMap<>();
        for (AgentMode mode : AgentMode.values()) modes.put(mode, summarize(cases.stream().filter(c -> c.task().mode() == mode).toList()));
        Map<String, Summary> tags = new LinkedHashMap<>();
        AgentBenchmarkDataset.TAGS.stream().sorted().forEach(tag -> tags.put(tag, summarize(cases.stream().filter(c -> c.task().tags().contains(tag)).toList())));
        boolean live = "LIVE".equals(configuration.get("mode"));
        String status = overall.notRun() > 0 ? "INCOMPLETE_RUN" : live && !Boolean.TRUE.equals(configuration.get("corpusStable"))
                ? "INVALID_CORPUS" : overall.pendingReview() > 0 || overall.unverifiedLabels() > 0 ? "NEEDS_HUMAN_REVIEW" : "COMPLETE";
        String target = target(overall, configuration);
        Map<AgentMode, String> modeTargets = new LinkedHashMap<>();
        modes.forEach((mode, summary) -> modeTargets.put(mode, target(summary, configuration)));
        List<FailureDetail> failures = cases.stream().filter(c -> c.evaluation().status() == Status.FAILED
                || c.evaluation().status() == Status.ERROR).map(c -> new FailureDetail(c.task().caseId(), c.task().mode(),
                c.task().turns(), c.evaluation().status(), c.evaluation().failureReasons(), c.observation().turns())).toList();
        return new AgentBenchmarkReport("m19c-report-v2", AgentBenchmarkEvaluator.POLICY_VERSION, runId, createdAt,
                Instant.now().toString(), dataset, configuration, limitations, status, target, overall, modes, modeTargets, tags, cases, failures,
                integrity(runId, createdAt, dataset, configuration, limitations, cases));
    }

    private static String target(Summary summary, Map<String, Object> configuration) {
        if (!"LIVE".equals(configuration.get("mode")) || !Boolean.TRUE.equals(configuration.get("corpusStable"))
                || summary.taskSuccessRate() == null) return "NOT_EVALUABLE";
        return summary.taskSuccessRate() >= TARGET_SUCCESS_RATE ? "MET" : "NOT_MET";
    }

    static String integrity(String runId, String createdAt, DatasetInfo dataset, Map<String, Object> configuration,
                            List<String> limitations, List<CaseResult> cases) {
        return AgentBenchmarkText.canonicalHash(new tools.jackson.databind.json.JsonMapper(), List.of(runId, createdAt,
                dataset, configuration, limitations, cases.stream().map(c -> List.of(c.task(), c.observation())).toList()));
    }

    private static Summary summarize(List<CaseResult> cases) {
        int success = count(cases, Status.SUCCEEDED), pending = count(cases, Status.PENDING_REVIEW), notRun = count(cases, Status.NOT_RUN);
        int unverified = (int) cases.stream().filter(c -> c.evaluation().humanReview() == null
                || !Boolean.TRUE.equals(c.evaluation().humanReview().referenceVerified())).count();
        int executed = (int) cases.stream().filter(c -> c.observation().executed()).count();
        int faultApplied = (int) cases.stream().filter(c -> c.observation().turns().stream().flatMap(t -> t.dispatches().stream())
                .anyMatch(d -> d.action() != com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction.RUN)).count();
        return new Summary(cases.size(), executed, success, count(cases, Status.FAILED), count(cases, Status.ERROR),
                pending, notRun, unverified, cases.isEmpty() || pending > 0 || notRun > 0 || unverified > 0 ? null : (double) success / cases.size(),
                cases.isEmpty() ? null : (double) success / cases.size(),
                cases.isEmpty() ? null : (double) (success + pending + notRun) / cases.size(), faultApplied,
                cases.isEmpty() ? null : (double) (success + pending) / cases.size());
    }
    private static int count(List<CaseResult> cases, Status status) { return (int) cases.stream().filter(c -> c.evaluation().status() == status).count(); }

    public record DatasetInfo(String path, String sha256, long knowledgeBaseId, int tasks, String labelProvenance,
                              String referencedChunkFingerprint) { }
    public record CaseResult(Task task, Observation observation, String resultSha256, Evaluation evaluation) { }
    public record Summary(int tasks, int executed, int succeeded, int failed, int errors, int pendingReview, int notRun,
                          int unverifiedLabels, Double taskSuccessRate, Double successRateLowerBound,
                          Double successRateUpperBound, int faultAppliedTasks, Double automaticGatePassRate) { }
    public record FailureDetail(String caseId, AgentMode mode, List<String> questions, Status status,
                                List<String> failureReasons, List<Turn> turns) { }
}
