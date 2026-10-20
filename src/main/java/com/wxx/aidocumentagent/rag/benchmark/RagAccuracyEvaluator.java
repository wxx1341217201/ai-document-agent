package com.wxx.aidocumentagent.rag.benchmark;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import com.wxx.aidocumentagent.rag.application.RagRetrievalTrace;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset.Sample;
import tools.jackson.databind.ObjectMapper;

/** 结构检查可自动否决；事实语义、关键冲突、核心陈述支撑必须由绑定本次输出的人工复核确定。 */
public final class RagAccuracyEvaluator {

    public static final String POLICY_VERSION = "m19b-human-final-v2";
    private static final Pattern CITATION = Pattern.compile("\\[C[1-9]\\d*]");
    private static final Pattern CITATION_SHAPED = Pattern.compile("\\[C[^]]*]", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper;

    public RagAccuracyEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public String resultHash(String runId, String datasetSha256, Sample sample, Observation observation) {
        return BenchmarkText.sha256(mapper.writeValueAsString(List.of(runId, datasetSha256, sample, observation)));
    }

    public Evaluation evaluate(String runId, String datasetSha256, Sample sample, Observation observation,
                               Review review) {
        if (review != null) {
            validateReview(runId, datasetSha256, sample, observation, review);
        }
        if (observation.errorCode() != null) {
            Status status = observation.executed() ? Status.ERROR : Status.NOT_RUN;
            return new Evaluation(status, null, null, List.of(), List.of(observation.errorCode()), review);
        }
        List<String> reasons = citationFailures(sample, observation);
        List<String> suggestedFacts = sample.requiredFacts().stream()
                .filter(fact -> fact.acceptedPhrases().stream()
                        .anyMatch(alias -> BenchmarkText.containsPhrase(observation.answer(), alias)))
                .map(RagAccuracyDataset.RequiredFact::id).toList();
        Double preliminaryCoverage = sample.answerable()
                ? (double) suggestedFacts.size() / sample.requiredFacts().size() : null;
        if (!reasons.isEmpty()) {
            return new Evaluation(incorrect(sample), preliminaryCoverage, null, suggestedFacts, reasons, review);
        }
        if (review == null || !Boolean.TRUE.equals(review.referenceVerified())) {
            return new Evaluation(Status.PENDING_REVIEW, preliminaryCoverage, null, suggestedFacts,
                    List.of(review == null ? "HUMAN_REVIEW_REQUIRED" : "REFERENCE_LABELS_NOT_VERIFIED"), review);
        }
        if (!sample.answerable()) {
            boolean correct = Boolean.TRUE.equals(review.refusalCorrect());
            return new Evaluation(correct ? Status.REFUSAL_CORRECT : Status.REFUSAL_INCORRECT,
                    null, null, List.of(), correct ? List.of() : List.of("REFUSAL_INCORRECT"), review);
        }

        Map<String, RagCitationResponse> used = citationsById(observation.citations());
        Set<String> coveredFacts = new HashSet<>();
        for (FactReview factReview : review.facts()) {
            if (!factReview.covered()) {
                continue;
            }
            var fact = sample.requiredFacts().stream().filter(item -> item.id().equals(factReview.factId()))
                    .findFirst().orElseThrow();
            boolean supported = !factReview.citationIds().isEmpty() && factReview.citationIds().stream()
                    .allMatch(id -> used.containsKey(id) && fact.supportingChunkIds().contains(used.get(id).chunkId()));
            if (!supported) {
                reasons.add("FACT_CITATION_UNSUPPORTED:" + fact.id());
            }
            else {
                coveredFacts.add(fact.id());
            }
        }
        double coverage = (double) coveredFacts.size() / sample.requiredFacts().size();
        if (coverage < sample.factCoverageThreshold()) {
            reasons.add("REQUIRED_FACT_COVERAGE_BELOW_THRESHOLD");
        }
        if (!Boolean.TRUE.equals(review.criticalConflictFree())) {
            reasons.add("CRITICAL_FACT_CONFLICT");
        }
        if (!Boolean.TRUE.equals(review.coreClaimsSupported())) {
            reasons.add("CORE_CLAIMS_NOT_SUPPORTED_BY_CITATIONS");
        }
        return new Evaluation(reasons.isEmpty() ? Status.CORRECT : Status.INCORRECT,
                preliminaryCoverage, coverage, suggestedFacts, reasons, review);
    }

    private List<String> citationFailures(Sample sample, Observation observation) {
        List<String> failures = new ArrayList<>();
        if (observation.answer() == null || observation.answer().isBlank()) {
            return new ArrayList<>(List.of("EMPTY_VISIBLE_ANSWER"));
        }
        Map<String, RagEvidenceChunk> context = new HashMap<>();
        Set<Long> chunkIds = new HashSet<>();
        for (RagEvidenceChunk evidence : observation.evidence()) {
            String id = evidence.citation().citationId();
            if (evidence.knowledgeBaseId() != sample.knowledgeBaseId() || id == null || !id.matches("C[1-9]\\d*")
                    || context.putIfAbsent(id, evidence) != null || !chunkIds.add(evidence.citation().chunkId())) {
                failures.add("INVALID_OR_FOREIGN_CONTEXT");
            }
        }
        Map<String, RagCitationResponse> used = citationsById(observation.citations());
        if (used.size() != observation.citations().size()) {
            failures.add("DUPLICATE_CITATION_METADATA");
        }
        Set<String> tokens = new HashSet<>();
        Matcher matcher = CITATION.matcher(observation.answer());
        while (matcher.find()) {
            tokens.add(matcher.group().substring(1, matcher.group().length() - 1));
        }
        Matcher shaped = CITATION_SHAPED.matcher(observation.answer());
        while (shaped.find()) {
            if (!CITATION.matcher(shaped.group()).matches()) {
                failures.add("MALFORMED_CITATION_TOKEN");
            }
        }
        if (!tokens.equals(used.keySet())) {
            failures.add("ANSWER_CITATION_METADATA_MISMATCH");
        }
        for (RagCitationResponse citation : observation.citations()) {
            RagEvidenceChunk evidence = context.get(citation.citationId());
            if (evidence == null || !evidence.citation().equals(citation)) {
                failures.add("CITATION_NOT_IN_CURRENT_CONTEXT:" + citation.citationId());
            }
            if (sample.answerable() && !sample.acceptableChunkIds().contains(citation.chunkId())) {
                failures.add("CITATION_OUTSIDE_ACCEPTABLE_RANGE:" + citation.citationId());
            }
        }
        if (sample.answerable() && used.isEmpty()) {
            failures.add("ANSWERABLE_REQUIRES_REAL_CITATION");
        }
        return failures.stream().distinct().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void validateReview(String runId, String datasetSha256, Sample sample, Observation observation,
                                Review review) {
        if (!Objects.equals(review.runId(), runId) || !Objects.equals(review.datasetSha256(), datasetSha256)
                || !Objects.equals(review.caseId(), sample.id())
                || !Objects.equals(review.resultSha256(), resultHash(runId, datasetSha256, sample, observation))
                || review.reviewer() == null || review.reviewer().isBlank()
                || review.referenceVerified() == null || review.reviewedAt() == null) {
            throw new IllegalArgumentException("Unbound or incomplete human review: " + sample.id());
        }
        Instant.parse(review.reviewedAt());
        Set<String> expectedFacts = new HashSet<>();
        sample.requiredFacts().forEach(fact -> expectedFacts.add(fact.id()));
        Set<String> reviewedFacts = new HashSet<>();
        for (FactReview fact : review.facts()) {
            if (fact.covered() == null || !reviewedFacts.add(fact.factId())
                    || (!fact.covered() && !fact.citationIds().isEmpty())) {
                throw new IllegalArgumentException("Invalid fact review: " + sample.id());
            }
        }
        if (!expectedFacts.equals(reviewedFacts) || (sample.answerable()
                ? review.criticalConflictFree() == null || review.coreClaimsSupported() == null
                : review.refusalCorrect() == null)) {
            throw new IllegalArgumentException("Human review must assess every fact and all correctness gates: " + sample.id());
        }
    }

    private Map<String, RagCitationResponse> citationsById(List<RagCitationResponse> citations) {
        Map<String, RagCitationResponse> result = new HashMap<>();
        citations.forEach(item -> result.putIfAbsent(item.citationId(), item));
        return result;
    }

    private Status incorrect(Sample sample) {
        return sample.answerable() ? Status.INCORRECT : Status.REFUSAL_INCORRECT;
    }

    public enum Status { CORRECT, INCORRECT, REFUSAL_CORRECT, REFUSAL_INCORRECT, PENDING_REVIEW, ERROR, NOT_RUN }

    public record Observation(boolean executed, String answer, List<RagCitationResponse> citations,
                              List<RagEvidenceChunk> evidence, String promptSha256, String modelName,
                              Boolean modelCalled, boolean degraded, double elapsedMillis, String errorCode,
                              @JsonInclude(JsonInclude.Include.NON_NULL) RagRetrievalTrace retrievalTrace) {

        public Observation {
            citations = citations == null ? List.of() : List.copyOf(citations);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            answer = BenchmarkText.visibleAnswer(answer);
            if (!Double.isFinite(elapsedMillis) || elapsedMillis < 0) {
                throw new IllegalArgumentException("Invalid elapsedMillis");
            }
        }

        public Observation(boolean executed, String answer, List<RagCitationResponse> citations,
                           List<RagEvidenceChunk> evidence, String promptSha256, String modelName,
                           Boolean modelCalled, boolean degraded, double elapsedMillis, String errorCode) {
            this(executed, answer, citations, evidence, promptSha256, modelName, modelCalled, degraded,
                    elapsedMillis, errorCode, null);
        }

        public static Observation notRun(String reason) {
            return new Observation(false, null, List.of(), List.of(), null, null, false, false, 0, reason);
        }
    }

    public record FactReview(String factId, Boolean covered, List<String> citationIds) {
        public FactReview {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }

    public record Review(String runId, String datasetSha256, String caseId, String resultSha256,
                         String reviewer, String reviewedAt, Boolean referenceVerified,
                         List<FactReview> facts, Boolean criticalConflictFree, Boolean coreClaimsSupported,
                         Boolean refusalCorrect, String notes) {
        public Review {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    public record Evaluation(Status status, Double preliminaryFactCoverage, Double reviewedFactCoverage,
                             List<String> suggestedFactIds, List<String> failureReasons, Review humanReview) {
        public Evaluation {
            suggestedFactIds = List.copyOf(suggestedFactIds);
            failureReasons = List.copyOf(failureReasons);
        }
    }
}
