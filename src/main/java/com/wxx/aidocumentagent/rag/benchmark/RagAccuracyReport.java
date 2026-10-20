package com.wxx.aidocumentagent.rag.benchmark;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset.QuestionType;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset.Sample;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Evaluation;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Observation;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Status;

public record RagAccuracyReport(String schemaVersion, String runId, String createdAt, String evaluatedAt,
                                 String policyVersion, DatasetInfo dataset, Map<String, Object> configuration,
                                 List<String> limitations, String status, String targetStatus, Summary overall,
                                 Map<QuestionType, Summary> byType, List<CaseResult> cases,
                                 List<FailureDetail> failureDetails) {

    public static final double QA_ACCURACY_TARGET = 0.83;

    public RagAccuracyReport {
        configuration = Map.copyOf(configuration);
        limitations = List.copyOf(limitations);
        byType = Map.copyOf(byType);
        cases = List.copyOf(cases);
        failureDetails = List.copyOf(failureDetails);
    }

    public static RagAccuracyReport build(String runId, String createdAt, DatasetInfo dataset,
                                           Map<String, Object> configuration, List<String> limitations,
                                           List<CaseResult> cases) {
        Summary overall = summarize(cases);
        Map<QuestionType, Summary> byType = new LinkedHashMap<>();
        for (QuestionType type : QuestionType.values()) {
            byType.put(type, summarize(cases.stream().filter(item -> item.sample().type() == type).toList()));
        }
        boolean invalid = overall.errors() > 0 || overall.notRun() > 0 || overall.degraded() > 0
                || Boolean.FALSE.equals(configuration.get("corpusStable"));
        String status = invalid ? "INVALID_OR_INCOMPLETE_RUN"
                : overall.pendingReview() > 0 ? "NEEDS_HUMAN_REVIEW" : "COMPLETE";
        String target = !status.equals("COMPLETE") || !"LIVE".equals(configuration.get("mode"))
                || overall.qaAccuracy() == null ? "NOT_EVALUABLE"
                : overall.qaAccuracy() >= QA_ACCURACY_TARGET ? "MET" : "NOT_MET";
        List<FailureDetail> failures = cases.stream()
                .filter(item -> item.evaluation().status() != Status.CORRECT
                        && item.evaluation().status() != Status.REFUSAL_CORRECT
                        && item.evaluation().status() != Status.PENDING_REVIEW)
                .map(item -> new FailureDetail(item.sample().id(), item.sample().question(), item.sample().type(),
                        item.observation().answer(), item.observation().citations(), item.evaluation().status(),
                        item.evaluation().failureReasons())).toList();
        return new RagAccuracyReport("m19b-report-v1", runId, createdAt, Instant.now().toString(),
                RagAccuracyEvaluator.POLICY_VERSION, dataset, configuration, limitations, status, target,
                overall, byType, cases, failures);
    }

    private static Summary summarize(List<CaseResult> cases) {
        int answerable = (int) cases.stream().filter(item -> item.sample().answerable()).count();
        int unanswerable = cases.size() - answerable;
        int correct = count(cases, Status.CORRECT);
        int refusalCorrect = count(cases, Status.REFUSAL_CORRECT);
        int pendingQa = (int) cases.stream().filter(item -> item.sample().answerable() && needsReview(item)).count();
        int pendingRefusal = (int) cases.stream().filter(item -> !item.sample().answerable() && needsReview(item)).count();
        int notRunQa = (int) cases.stream().filter(item -> item.sample().answerable()
                && item.evaluation().status() == Status.NOT_RUN).count();
        int notRunRefusal = count(cases, Status.NOT_RUN) - notRunQa;
        int undecidedQa = (int) cases.stream().filter(item -> item.sample().answerable()
                && item.evaluation().status() == Status.PENDING_REVIEW).count();
        Double qaAccuracy = answerable == 0 || pendingQa > 0 || notRunQa > 0
                ? null : (double) correct / answerable;
        Double refusalAccuracy = unanswerable == 0 || pendingRefusal > 0 || notRunRefusal > 0
                ? null : (double) refusalCorrect / unanswerable;
        int reviewed = (int) cases.stream().filter(item -> item.evaluation().humanReview() != null
                && Boolean.TRUE.equals(item.evaluation().humanReview().referenceVerified())).count();
        int degraded = (int) cases.stream().filter(item -> item.observation().degraded()).count();
        return new Summary(cases.size(), answerable, correct, qaAccuracy,
                answerable == 0 ? null : (double) correct / answerable,
                answerable == 0 ? null : (double) (correct + undecidedQa + notRunQa) / answerable,
                unanswerable, refusalCorrect, refusalAccuracy, reviewed, pendingQa + pendingRefusal,
                count(cases, Status.INCORRECT), count(cases, Status.REFUSAL_INCORRECT),
                count(cases, Status.ERROR), count(cases, Status.NOT_RUN), degraded);
    }

    private static boolean needsReview(CaseResult item) {
        return item.evaluation().status() == Status.PENDING_REVIEW || (item.observation().executed()
                && item.observation().errorCode() == null && (item.evaluation().humanReview() == null
                || !Boolean.TRUE.equals(item.evaluation().humanReview().referenceVerified())));
    }

    private static int count(List<CaseResult> cases, Status status) {
        return (int) cases.stream().filter(item -> item.evaluation().status() == status).count();
    }

    public record DatasetInfo(String path, String sha256, long knowledgeBaseId, int samples,
                              String labelProvenance, String referencedChunkFingerprint) { }

    public record CaseResult(Sample sample, Observation observation, String resultSha256, Evaluation evaluation) { }

    public record Summary(int samples, int answerable, int correct, Double qaAccuracy,
                          Double qaAccuracyLowerBound, Double qaAccuracyUpperBound,
                          int unanswerable, int refusalCorrect, Double refusalAccuracy,
                          int humanReviewed, int pendingReview, int incorrect, int refusalIncorrect,
                          int errors, int notRun, int degraded) { }

    public record FailureDetail(String caseId, String question, QuestionType type, String answer,
                                List<RagCitationResponse> citations, Status status, List<String> failureReasons) { }
}
