package com.wxx.aidocumentagent.ingestion.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RabbitBenchmarkEvaluatorTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private Map<String, Object> raw(boolean burst, int duration, boolean corrupt) {
        var docs = new ArrayList<Map<String, Object>>();
        var stages = new ArrayList<Map<String, Object>>();
        var clients = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            double ready = burst ? 30000 + i * 10 : 800 * (i + 1);
            docs.add(Map.of("documentId", i, "corpusId", "doc-%03d".formatted(i),
                    "arrivedAt", ready - 100, "ackAt", ready - 50, "readyAt", ready));
            stages.add(Map.of("batchId", "b" + i, "stage", "VECTOR_INDEX", "documentId", i, "chunks", 1,
                    "enqueuedAt", ready - 80, "startedAt", ready - 60, "completedAt", ready - 40));
            stages.add(Map.of("batchId", "b" + i, "stage", "KEYWORD_INDEX", "documentId", i, "chunks", 1,
                    "enqueuedAt", ready - 30, "startedAt", ready - 20, "completedAt", ready));
            clients.add(Map.of("finishedAt", ready + 10, "success", true, "httpLatencyMs", 55));
        }
        var samples = new ArrayList<Map<String, Object>>();
        for (int i = 0; i <= duration; i++) samples.add(Map.of("at", i * 1000, "batchBacklog", 10));
        return new LinkedHashMap<>(Map.of("documents", docs, "stages", stages, "clientObservations", clients,
                "samples", samples, "measurementStartedAt", 0, "admissionStoppedAt", duration * 1000,
                "verification", Map.of("persistedChunks", 100, "indexedChunks", 100, "incompleteIndexes", 0,
                        "mismatchedIndexes", corrupt ? 1 : 0, "pendingDocuments", 0, "failedDocuments", 0)));
    }

    @Test void usesWholeStableWindowAndPreservesLogicalBatchBoundaries() {
        var result = RabbitBenchmarkEvaluator.evaluate(mapper.valueToTree(raw(false, 90, false)), 60, .01);
        assertThat(result.get("valid")).isEqualTo(true);
        JsonNode tree = mapper.valueToTree(result);
        assertThat(tree.path("peakStableWindow").path("documentsPerSecond").asDouble()).isEqualTo(1.25);
        assertThat(tree.path("logicalBatches").size()).isEqualTo(100);
        assertThat(tree.path("queueWaitLatencyMs").path("mean").asDouble()).isEqualTo(20);
        assertThat(tree.path("processingLatencyMs").path("mean").asDouble()).isEqualTo(60);
        assertThat(tree.path("submitAckLatencyMs").path("mean").asDouble()).isEqualTo(50);
        assertThat(tree.path("endToEndLatencyMs").path("mean").asDouble()).isEqualTo(100);
    }
    @Test void rejectsShortDurationAndInstantaneousBurst() {
        assertThat(RabbitBenchmarkEvaluator.evaluate(mapper.valueToTree(raw(false, 45, false)), 60, .01).get("peakStableWindow")).isNull();
        assertThat(RabbitBenchmarkEvaluator.evaluate(mapper.valueToTree(raw(true, 90, false)), 60, .01).get("peakStableWindow")).isNull();
    }
    @Test void rejectsCorrectCountsWithCorruptIndexContent() {
        var result = RabbitBenchmarkEvaluator.evaluate(mapper.valueToTree(raw(false, 90, true)), 60, .01);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("peakStableWindow")).isNull();
    }
    @Test void retainsFailuresAndUsesNearestRankP95() {
        var raw = raw(false, 90, false);
        @SuppressWarnings("unchecked") var clients = (List<Map<String, Object>>) raw.get("clientObservations");
        clients.set(0, Map.of("finishedAt", 1200, "success", false));
        var result = RabbitBenchmarkEvaluator.evaluate(mapper.valueToTree(raw), 60, .01);
        assertThat(result.get("errors")).isEqualTo(1);
        assertThat(result.get("errorRate")).isEqualTo(.01);
        var values = java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(i -> (double) i).toList();
        assertThat(RabbitBenchmarkEvaluator.distribution(values).get("p95")).isEqualTo(95.0);
        assertThat(RabbitBenchmarkEvaluator.distribution(List.of()).get("mean")).isNull();
    }
    @Test void partialMatrixCannotClaimFormalTarget() {
        var evaluation = RabbitBenchmarkEvaluator.evaluate(mapper.valueToTree(raw(false, 90, false)), 60, .01);
        var rows = List.of(Map.of("config", Map.of("mode", "sync", "batchSize", 50, "consumers", 1), "evaluation", evaluation, "cleanup", "COMPLETED"),
                Map.of("config", Map.of("mode", "async", "batchSize", 50, "consumers", 4), "evaluation", evaluation, "cleanup", "COMPLETED"));
        var summary = RabbitBenchmarkReport.summary(mapper.valueToTree(Map.of("matrix", rows)), mapper.valueToTree(rows));
        assertThat(summary.path("throughputGain").asDouble()).isEqualTo(1);
        assertThat(summary.path("throughputTarget").asString()).isEqualTo("NOT_EVALUABLE");
        assertThat(summary.path("waitTarget").asString()).isEqualTo("NOT_EVALUABLE");
    }
}
