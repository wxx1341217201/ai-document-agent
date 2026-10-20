package com.wxx.aidocumentagent.rag.benchmark;

import java.util.List;

import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.FactReview;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Observation;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Review;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Status;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagAccuracyEvaluatorTest {

    private final RagAccuracyEvaluator evaluator = new RagAccuracyEvaluator(new JsonMapper());
    private final RagAccuracyDataset.Sample sample = RagAccuracyDatasetTest.sample("S1", 2);
    private final RagCitationResponse citation = new RagCitationResponse("C1", 10, "source.txt", 1, null, null, "golden key");
    private final RagEvidenceChunk evidence = new RagEvidenceChunk(2, citation, "A golden key is on the table.", "a".repeat(64));

    @Test
    void keywordMatchesAloneCannotProduceCorrectOrFinalAccuracy() {
        var observation = observe("golden key [C1]", List.of(citation), List.of(evidence));
        var evaluation = evaluate(observation, null);
        assertThat(evaluation.preliminaryFactCoverage()).isEqualTo(1.0);
        assertThat(evaluation.status()).isEqualTo(Status.PENDING_REVIEW);
        assertThat(evaluation.failureReasons()).containsExactly("HUMAN_REVIEW_REQUIRED");
    }

    @Test
    void correctRequiresAllFourGatesAndBoundHumanReview() {
        var observation = observe("golden key [C1]", List.of(citation), List.of(evidence));
        assertThat(evaluate(observation, review(observation, true, true, true)).status()).isEqualTo(Status.CORRECT);
        assertThat(evaluate(observation, review(observation, false, true, true)).failureReasons())
                .contains("REQUIRED_FACT_COVERAGE_BELOW_THRESHOLD");
        assertThat(evaluate(observation, review(observation, true, false, true)).failureReasons())
                .contains("CRITICAL_FACT_CONFLICT");
        assertThat(evaluate(observation, review(observation, true, true, false)).failureReasons())
                .contains("CORE_CLAIMS_NOT_SUPPORTED_BY_CITATIONS");
    }

    @Test
    void humanReviewCanRecognizeAValidParaphraseAbsentFromAliasList() {
        var observation = observe("A small key made of gold [C1]", List.of(citation), List.of(evidence));
        var evaluation = evaluate(observation, review(observation, true, true, true));
        assertThat(evaluation.preliminaryFactCoverage()).isZero();
        assertThat(evaluation.reviewedFactCoverage()).isEqualTo(1.0);
        assertThat(evaluation.status()).isEqualTo(Status.CORRECT);
    }

    @Test
    void rejectsInventedCitationAndForgedMetadataEvenWithHumanApproval() {
        var observation = observe("golden key [C9]", List.of(citation), List.of(evidence));
        assertThat(evaluate(observation, review(observation, true, true, true)).failureReasons())
                .contains("ANSWER_CITATION_METADATA_MISMATCH");
        var forged = new RagCitationResponse("C1", 10, "source.txt", 1, null, null, "fabricated quote");
        var altered = observe("golden key [C1]", List.of(forged), List.of(evidence));
        assertThat(evaluate(altered, null).failureReasons()).contains("CITATION_NOT_IN_CURRENT_CONTEXT:C1");
        var noCitation = observe("golden key", List.of(), List.of(evidence));
        assertThat(evaluate(noCitation, null).failureReasons()).contains("ANSWERABLE_REQUIRES_REAL_CITATION");
    }

    @Test
    void rejectsForeignKnowledgeBaseAndChunksOutsideAcceptedRange() {
        var foreign = new RagEvidenceChunk(3, citation, evidence.contextText(), evidence.sourceContentSha256());
        assertThat(evaluate(observe("golden key [C1]", List.of(citation), List.of(foreign)), null).failureReasons())
                .contains("INVALID_OR_FOREIGN_CONTEXT");
        var wrong = new RagCitationResponse("C1", 10, "source.txt", 99, null, null, "golden key");
        var wrongEvidence = new RagEvidenceChunk(2, wrong, evidence.contextText(), "a".repeat(64));
        assertThat(evaluate(observe("golden key [C1]", List.of(wrong), List.of(wrongEvidence)), null).failureReasons())
                .contains("CITATION_OUTSIDE_ACCEPTABLE_RANGE:C1");
    }

    @Test
    void factSupportMustUseACitationActuallyReturnedWithThisAnswer() {
        var observation = observe("golden key [C1]", List.of(citation), List.of(evidence));
        var review = new Review("run", "dataset", "S1", hash(observation), "reviewer", "2026-10-02T12:00:00Z",
                true, List.of(new FactReview("F1", true, List.of("C2"))), true, true, null, "checked");
        assertThat(evaluate(observation, review).failureReasons()).contains("FACT_CITATION_UNSUPPORTED:F1");
    }

    @Test
    void staleOrPartialHumanReviewCannotBeReusedForNewAnswer() {
        var observation = observe("golden key [C1]", List.of(citation), List.of(evidence));
        var changed = observe("golden key and extra claim [C1]", List.of(citation), List.of(evidence));
        assertThatThrownBy(() -> evaluate(changed, review(observation, true, true, true)))
                .hasMessageContaining("Unbound");
        var incomplete = new Review("run", "dataset", "S1", hash(observation), "reviewer", "2026-10-02T12:00:00Z",
                true, List.of(), true, true, null, "");
        assertThatThrownBy(() -> evaluate(observation, incomplete)).hasMessageContaining("every fact");
    }

    @Test
    void unverifiedReferenceLabelsRemainPending() {
        var observation = observe("golden key [C1]", List.of(citation), List.of(evidence));
        var draft = new Review("run", "dataset", "S1", hash(observation), "reviewer", "2026-10-02T12:00:00Z",
                false, List.of(new FactReview("F1", true, List.of("C1"))), true, true, null, "bad label");
        assertThat(evaluate(observation, draft).status()).isEqualTo(Status.PENDING_REVIEW);
    }

    @Test
    void refusalCorrectnessIsSeparateAndRequiresHumanAssessment() {
        var noAnswer = new RagAccuracyDataset.Sample("N1", 2, "email?", RagAccuracyDataset.QuestionType.UNANSWERABLE,
                false, "我不知道", List.of(), 1.0, List.of(), List.of(), "draft");
        var observation = observe("我不知道。现有资料不足，请补充相关资料。", List.of(), List.of());
        var review = new Review("run", "dataset", "N1", evaluator.resultHash("run", "dataset", noAnswer, observation),
                "reviewer", "2026-10-02T12:00:00Z", true, List.of(), null, null, true, "no answer in corpus");
        assertThat(evaluator.evaluate("run", "dataset", noAnswer, observation, null).status()).isEqualTo(Status.PENDING_REVIEW);
        assertThat(evaluator.evaluate("run", "dataset", noAnswer, observation, review).status()).isEqualTo(Status.REFUSAL_CORRECT);
    }

    @Test
    void stripsThinkBlocksAndRejectsUnclosedReasoningBeforeReports() {
        assertThat(observe("<think>hidden reasoning</think>golden key [C1]", List.of(citation), List.of(evidence)).answer())
                .isEqualTo("golden key [C1]");
        assertThatThrownBy(() -> observe("<think>hidden reasoning", List.of(), List.of()))
                .hasMessage("UNSAFE_MODEL_OUTPUT");
        assertThat(BenchmarkText.containsPhrase("Catherine", "cat")).isFalse();
    }

    private Observation observe(String answer, List<RagCitationResponse> citations, List<RagEvidenceChunk> context) {
        return new Observation(true, answer, citations, context, "p".repeat(64), "fake-test-model", true, false, 1, null);
    }

    @Test
    void retrievalTraceIsBoundToReviewButLegacyObservationSerializationRemainsUnchanged() {
        var original = observe("golden key [C1]", List.of(citation), List.of(evidence));
        assertThat(new JsonMapper().writeValueAsString(original)).doesNotContain("retrievalTrace");
        var trace = new com.wxx.aidocumentagent.rag.application.RagRetrievalTrace("bounded-query-expansion-v1",
                List.of("key on table"), 0);
        var changed = new Observation(original.executed(), original.answer(), original.citations(), original.evidence(),
                original.promptSha256(), original.modelName(), original.modelCalled(), original.degraded(),
                original.elapsedMillis(), original.errorCode(), trace);
        assertThat(hash(changed)).isNotEqualTo(hash(original));
        assertThatThrownBy(() -> evaluate(changed, review(original, true, true, true)))
                .hasMessageContaining("Unbound or incomplete human review");
    }

    private Review review(Observation observation, boolean covered, boolean conflictFree, boolean supported) {
        return new Review("run", "dataset", "S1", hash(observation), "reviewer", "2026-10-02T12:00:00Z", true,
                List.of(new FactReview("F1", covered, covered ? List.of("C1") : List.of())), conflictFree, supported, null, "checked");
    }

    private String hash(Observation observation) {
        return evaluator.resultHash("run", "dataset", sample, observation);
    }

    private RagAccuracyEvaluator.Evaluation evaluate(Observation observation, Review review) {
        return evaluator.evaluate("run", "dataset", sample, observation, review);
    }
}
