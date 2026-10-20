package com.wxx.aidocumentagent.retrieval.benchmark;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class RetrievalBenchmarkMetricsTest {
    @Test
    void normalizesMultipleRelevantChunksAndTruncatesAtFive() {
        var cases = List.of(new RetrievalBenchmarkDataset.Case("Q1", 2, "question", true,
                List.of(7L, 8L, 9L), List.of("cross_context")));
        var score = RetrievalBenchmarkMetrics.score(cases, Map.of("Q1", List.of(7L, 99L, 8L, 100L, 101L, 9L))).get("all");
        double ideal = 1 + Math.log(2) / Math.log(3) + 0.5;
        assertThat(score.recallAt5()).isEqualTo(1);
        assertThat(score.mrrAt5()).isEqualTo(1);
        assertThat(score.ndcgAt5()).isCloseTo(1.5 / ideal, offset(1e-12));
        assertThat(RetrievalBenchmarkMetrics.score(cases, Map.of("Q1", List.of(99L, 100L, 101L, 102L, 103L, 7L)))
                .get("all").recallAt5()).isZero();
    }

    @Test
    void computesHitRecallMrrNdcgAndTagBucketsWithoutNoAnswerDenominator() {
        List<RetrievalBenchmarkDataset.Case> cases = List.of(
                new RetrievalBenchmarkDataset.Case("Q1", 2, "first", true, List.of(7L), List.of("exact")),
                new RetrievalBenchmarkDataset.Case("Q2", 2, "second", true, List.of(8L), List.of("semantic")),
                new RetrievalBenchmarkDataset.Case("Q3", 2, "none", false, List.of(), List.of("unanswerable")));
        Map<String, List<Long>> predictions = Map.of(
                "Q1", List.of(99L, 99L, 7L, 100L), "Q2", List.of(99L), "Q3", List.of());

        Map<String, RetrievalBenchmarkMetrics.Score> scores = RetrievalBenchmarkMetrics.score(cases, predictions);

        assertThat(scores.get("all").answerable()).isEqualTo(2);
        assertThat(scores.get("all").recallAt5()).isEqualTo(0.5);
        assertThat(scores.get("all").mrrAt5()).isEqualTo(0.25);
        assertThat(scores.get("all").ndcgAt5()).isCloseTo(1D / Math.log(3D) * Math.log(2D) / 2D,
                offset(1e-12));
        assertThat(scores.get("all").unanswerable()).isEqualTo(1);
        assertThat(scores.get("all").emptyResultsForUnanswerable()).isEqualTo(1);
        assertThat(scores.get("exact").recallAt5()).isEqualTo(1D);
        assertThat(scores.get("semantic").recallAt5()).isZero();
    }

    @Test
    void usesNearestRankPercentilesOnSuccessfulSamples() {
        Map<String, Double> latency = RetrievalBenchmarkMetrics.latencyMillis(
                List.of(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L));

        assertThat(latency).containsEntry("p50", 2D).containsEntry("p95", 4D)
                .containsEntry("p99", 4D).containsEntry("average", 2.5D).containsEntry("max", 4D);
    }
}
