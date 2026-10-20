package com.wxx.aidocumentagent.ingestion.benchmark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** Evaluates completed work, never ACK counts or the highest instantaneous sample. */
public final class RabbitBenchmarkEvaluator {
    private RabbitBenchmarkEvaluator() { }

    public static Map<String, Object> distribution(List<Double> values) {
        var result = new LinkedHashMap<String, Object>();
        result.put("samples", values.size());
        if (values.isEmpty()) {
            for (String key : List.of("mean", "p50", "p95", "p99", "max")) result.put(key, null);
            return result;
        }
        var sorted = values.stream().sorted().toList();
        result.put("mean", sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        result.put("p50", percentile(sorted, .5));
        result.put("p95", percentile(sorted, .95));
        result.put("p99", percentile(sorted, .99));
        result.put("max", sorted.getLast());
        return result;
    }
    private static double percentile(List<Double> sorted, double quantile) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1));
    }

    public static Map<String, Object> evaluate(JsonNode raw, int windowSeconds, double errorThreshold) {
        var ack = new ArrayList<Double>();
        var e2e = new ArrayList<Double>();
        var wait = new ArrayList<Double>();
        var processing = new ArrayList<Double>();
        var http = new ArrayList<Double>();
        var batches = new HashMap<String, Map<String, JsonNode>>();
        var logical = new ArrayList<Map<String, Object>>();
        var seenCorpus = new java.util.HashSet<String>();
        boolean timestampsValid = true;
        for (JsonNode doc : raw.path("documents")) {
            if (!doc.path("corpusId").isMissingNode()) seenCorpus.add(doc.path("corpusId").asString());
            if (doc.path("arrivedAt").isNumber() && doc.path("ackAt").isNumber()) {
                double value = doc.path("ackAt").asDouble() - doc.path("arrivedAt").asDouble();
                ack.add(value);
                timestampsValid &= value >= 0;
            } else timestampsValid = false;
            if (doc.path("arrivedAt").isNumber() && doc.path("readyAt").isNumber()) {
                double value = doc.path("readyAt").asDouble() - doc.path("arrivedAt").asDouble();
                e2e.add(value);
                timestampsValid &= value >= 0;
            } else timestampsValid = false;
        }
        for (JsonNode stage : raw.path("stages")) batches.computeIfAbsent(stage.path("batchId").asString(),
                ignored -> new HashMap<>()).put(stage.path("stage").asString(), stage);
        for (var entry : batches.entrySet()) {
            JsonNode vector = entry.getValue().get("VECTOR_INDEX");
            JsonNode keyword = entry.getValue().get("KEYWORD_INDEX");
            if (vector == null || keyword == null || !keyword.path("completedAt").isNumber()) {
                timestampsValid = false;
                continue;
            }
            timestampsValid &= vector.path("enqueuedAt").isNumber() && vector.path("startedAt").isNumber()
                    && vector.path("completedAt").isNumber() && keyword.path("enqueuedAt").isNumber()
                    && keyword.path("startedAt").isNumber();
            double queued = vector.path("enqueuedAt").asDouble();
            double started = vector.path("startedAt").asDouble();
            double completed = keyword.path("completedAt").asDouble();
            timestampsValid &= started >= queued && completed >= started;
            wait.add(started - queued);
            processing.add(completed - started);
            logical.add(Map.of("batchId", entry.getKey(), "documentId", vector.path("documentId").asLong(),
                    "chunks", vector.path("chunks").asInt(), "enqueuedAt", queued,
                    "startedAt", started, "completedAt", completed,
                    "queueWaitMs", started - queued, "processingMs", completed - started));
        }
        int errors = 0;
        for (JsonNode client : raw.path("clientObservations")) {
            if (client.path("httpLatencyMs").isNumber()) http.add(client.path("httpLatencyMs").asDouble());
            if (!client.path("success").asBoolean()) errors++;
        }
        JsonNode verification = raw.path("verification");
        boolean valid = timestampsValid && seenCorpus.size() == 100
                && verification.path("persistedChunks").asLong(-1) == verification.path("indexedChunks").asLong(-2)
                && verification.path("incompleteIndexes").asLong(-1) == 0
                && verification.path("mismatchedIndexes").asLong(-1) == 0
                && verification.path("pendingDocuments").asLong(-1) == 0
                && verification.path("failedDocuments").asLong(-1) == 0;
        double start = raw.path("measurementStartedAt").asDouble();
        double stop = raw.path("admissionStoppedAt").asDouble();
        var windows = new ArrayList<Map<String, Object>>();
        var samples = new ArrayList<JsonNode>();
        raw.path("samples").forEach(samples::add);
        for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
            double from = samples.get(sampleIndex).path("at").asDouble();
            double to = from + windowSeconds * 1000.0;
            if (from < start || to > stop) continue;
            int endIndex = sampleIndex;
            while (endIndex < samples.size() && samples.get(endIndex).path("at").asDouble() < to) endIndex++;
            if (endIndex == samples.size()) continue;
            boolean coverage = samples.get(endIndex).path("at").asDouble() - to <= 2500;
            for (int j = sampleIndex + 1; j <= endIndex; j++) coverage &=
                    samples.get(j).path("at").asDouble() - samples.get(j - 1).path("at").asDouble() <= 2500;
            long docs = 0, count = 0, chunks = 0;
            var bins = new double[4];
            for (JsonNode doc : raw.path("documents")) {
                double at = doc.path("readyAt").asDouble(-1);
                if (at >= from && at < to) {
                    docs++;
                    bins[Math.min(3, (int) ((at - from) / (windowSeconds * 250.0)))]++;
                }
            }
            for (var batch : logical) {
                double at = (double) batch.get("completedAt");
                if (at >= from && at < to) { count++; chunks += (int) batch.get("chunks"); }
            }
            int failed = 0, finished = 0;
            for (JsonNode client : raw.path("clientObservations")) {
                double at = client.path("finishedAt").asDouble(-1);
                if (at >= from && at < to) { finished++; if (!client.path("success").asBoolean()) failed++; }
            }
            double errorRate = finished == 0 ? 1 : (double) failed / finished;
            double average = docs / 4.0;
            double variance = 0;
            for (double bin : bins) variance += (bin - average) * (bin - average) / 4;
            double cv = average == 0 ? Double.POSITIVE_INFINITY : Math.sqrt(variance) / average;
            long growth = samples.get(endIndex).path("batchBacklog").asLong()
                    - samples.get(sampleIndex).path("batchBacklog").asLong();
            // One long document may add up to 8 logical batches at batchSize=25.
            boolean stable = valid && coverage && docs > 0 && errorRate <= errorThreshold && cv <= .35 && growth <= 160;
            var window = new LinkedHashMap<String, Object>();
            window.put("from", from); window.put("to", to); window.put("stable", stable);
            window.put("documentsPerSecond", docs / (double) windowSeconds);
            window.put("batchesPerSecond", count / (double) windowSeconds);
            window.put("chunksPerSecond", chunks / (double) windowSeconds);
            window.put("errorRate", errorRate); window.put("completionBinCv", Double.isFinite(cv) ? cv : null);
            window.put("batchBacklogGrowth", growth); window.put("sampleCoverage", coverage);
            windows.add(window);
        }
        Map<String, Object> peak = windows.stream().filter(w -> (boolean) w.get("stable"))
                .max(java.util.Comparator.comparingDouble(w -> (double) w.get("documentsPerSecond"))).orElse(null);
        var result = new LinkedHashMap<String, Object>();
        result.put("valid", valid); result.put("uniqueCorpusDocuments", seenCorpus.size());
        result.put("submittedDocuments", raw.path("clientObservations").size()); result.put("errors", errors);
        result.put("errorRate", raw.path("clientObservations").isEmpty() ? null : (double) errors / raw.path("clientObservations").size());
        result.put("submitAckLatencyMs", distribution(ack)); result.put("clientHttpLatencyMs", distribution(http));
        result.put("queueWaitLatencyMs", distribution(wait)); result.put("processingLatencyMs", distribution(processing));
        result.put("endToEndLatencyMs", distribution(e2e)); result.put("logicalBatches", logical);
        var actualChunks = new ArrayList<Double>();
        var bytes = new ArrayList<Double>();
        long longDocuments = 0;
        for (JsonNode doc : raw.path("documents")) {
            if (doc.path("bytes").isNumber()) bytes.add(doc.path("bytes").asDouble());
            JsonNode chunks = raw.path("actualChunksPerDocument").path(doc.path("documentId").asString());
            if (chunks.isNumber()) {
                actualChunks.add(chunks.asDouble());
                if (chunks.asLong() >= 200) longDocuments++;
            }
        }
        result.put("actualChunksPerDocument", distribution(actualChunks));
        result.put("actualFileBytes", distribution(bytes));
        result.put("actualLongDocumentRatio", actualChunks.isEmpty() ? null : (double) longDocuments / actualChunks.size());
        var cpu = new ArrayList<Double>();
        var heap = new ArrayList<Double>();
        var queueDepth = new ArrayList<Double>();
        JsonNode previous = null;
        for (JsonNode point : samples) {
            if (point.path("heapUsedBytes").isNumber()) heap.add(point.path("heapUsedBytes").asDouble());
            queueDepth.add(point.path("batchBacklog").asDouble());
            if (previous != null && point.path("processCpuTimeNanos").isNumber() && previous.path("processCpuTimeNanos").isNumber()) {
                double dt = point.path("at").asDouble() - previous.path("at").asDouble();
                if (dt > 0) cpu.add(100.0 * (point.path("processCpuTimeNanos").asLong() - previous.path("processCpuTimeNanos").asLong()) / (dt * 1_000_000));
            }
            previous = point;
        }
        result.put("cpuOneCorePercent", distribution(cpu)); result.put("heapUsedBytes", distribution(heap));
        result.put("batchQueueDepth", distribution(queueDepth));
        result.put("windows", windows); result.put("peakStableWindow", peak);
        return result;
    }
}
