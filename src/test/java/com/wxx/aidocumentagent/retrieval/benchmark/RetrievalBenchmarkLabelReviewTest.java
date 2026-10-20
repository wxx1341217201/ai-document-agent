package com.wxx.aidocumentagent.retrieval.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalBenchmarkLabelReviewTest {
    @TempDir Path directory;
    private final JsonMapper mapper = new JsonMapper();
    private final RetrievalBenchmarkDataset dataset = new RetrievalBenchmarkDataset(List.of(
            new RetrievalBenchmarkDataset.Case("Q1", 2, "question", true, List.of(7L), List.of("exact"))), 2);

    @Test
    void untouchedTemplateRemainsPendingAndRejectsStaleEvidence() throws Exception {
        Path review = directory.resolve("review.jsonl");
        Files.writeString(review, RetrievalBenchmarkLabelReview.template(dataset, "d", "c", mapper));
        assertThat(RetrievalBenchmarkLabelReview.assess(review, dataset, "d", "c", mapper))
                .containsEntry("status", "PENDING_HUMAN_REVIEW");
        assertThatThrownBy(() -> RetrievalBenchmarkLabelReview.assess(review, dataset, "d", "changed", mapper))
                .hasMessageContaining("stale");
    }

    @Test
    void verifiedLabelsRequireReviewerAndTimestampAndBindToQuestion() throws Exception {
        var row = mapper.readTree(RetrievalBenchmarkLabelReview.template(dataset, "d", "c", mapper));
        var object = (tools.jackson.databind.node.ObjectNode) row;
        object.put("expectedLabelsVerified", true);
        Path review = directory.resolve("review.jsonl");
        Files.writeString(review, mapper.writeValueAsString(object));
        assertThatThrownBy(() -> RetrievalBenchmarkLabelReview.assess(review, dataset, "d", "c", mapper))
                .hasMessageContaining("reviewer");
        object.put("reviewer", "Test Reviewer");
        object.put("reviewedAt", Instant.now().toString());
        Files.writeString(review, mapper.writeValueAsString(object));
        assertThat(RetrievalBenchmarkLabelReview.assess(review, dataset, "d", "c", mapper))
                .containsEntry("status", "HUMAN_REVIEWED");
        var changed = new RetrievalBenchmarkDataset(List.of(new RetrievalBenchmarkDataset.Case("Q1", 2, "different",
                true, List.of(7L), List.of("exact"))), 2);
        assertThatThrownBy(() -> RetrievalBenchmarkLabelReview.assess(review, changed, "d", "c", mapper))
                .hasMessageContaining("stale");
    }

    @Test
    void fakeProfileCannotBecomeAchievementEvenWithHighScoresAndReviewedLabels() {
        var score = new RetrievalBenchmarkMetrics.Score(200, 200, 1, 1, 1, 0, 0);
        Map<String, Object> variants = Map.of(RetrievalBenchmarkRunner.Variant.RRF_CROSS_ENCODER.label,
                Map.of("status", "COMPLETE", "byTag", Map.of("all", score)));
        var latency = Map.<String, Object>of("statistics", Map.of("p95", 1D));
        assertThat(RetrievalBenchmarkRunner.targets(variants, latency, "FAKE_FIXED_LATENCY", Map.of("status", "HUMAN_REVIEWED")))
                .containsEntry("claimEligible", false).containsEntry("status", "REGRESSION_ONLY");
        assertThat(RetrievalBenchmarkRunner.targets(variants, latency, "LOCAL_REAL", Map.of("status", "PENDING_HUMAN_REVIEW")))
                .containsEntry("claimEligible", false).containsEntry("status", "PROVISIONAL_PENDING_HUMAN_LABEL_REVIEW");
    }
}
