package com.wxx.aidocumentagent.ingestion.benchmark;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Benchmark-only observations. Stage timestamps survive VECTOR -> KEYWORD timestamp resets. */
public class RabbitBenchmarkMetrics {
    private final Map<Long, DocumentSample> documents = new ConcurrentHashMap<>();
    private final Map<String, StageSample> stages = new ConcurrentHashMap<>();
    private volatile String phase = "warmup";
    private volatile double origin = now();

    public synchronized void begin(String phase) {
        this.phase = phase;
        documents.clear();
        stages.clear();
        origin = now();
    }

    public String phase() { return phase; }
    public double origin() { return origin; }
    public static double now() {
        Instant value = Instant.now();
        return value.getEpochSecond() * 1000.0 + value.getNano() / 1_000_000.0;
    }
    public static double time(LocalDateTime value) {
        Instant instant = value.toInstant(ZoneOffset.UTC);
        return instant.getEpochSecond() * 1000.0 + instant.getNano() / 1_000_000.0;
    }

    public void submitted(long id, String corpusId, int round, long bytes, double arrived) {
        DocumentSample sample = documents.computeIfAbsent(id, DocumentSample::new);
        sample.corpusId = corpusId;
        sample.round = round;
        sample.bytes = bytes;
        sample.arrivedAt = arrived;
    }
    public void acknowledged(long id, double ack) {
        documents.computeIfAbsent(id, DocumentSample::new).ackAt = ack;
    }
    public void ready(long id, double ready) {
        documents.computeIfAbsent(id, DocumentSample::new).readyAt = ready;
    }
    public void started(String batchId, String stage, long documentId, int chunks,
                        LocalDateTime enqueued, LocalDateTime started) {
        stages.computeIfAbsent(batchId + "/" + stage,
                ignored -> new StageSample(batchId, stage, documentId, chunks, time(enqueued), time(started)));
    }
    public void completed(String batchId, String stage, double completed) {
        StageSample sample = stages.get(batchId + "/" + stage);
        if (sample != null) sample.completedAt = completed;
    }
    public boolean isReady(long id) {
        DocumentSample value = documents.get(id);
        return value != null && value.readyAt != null;
    }
    public List<DocumentSample> documents() {
        return documents.values().stream().sorted(Comparator.comparingLong(d -> d.documentId)).toList();
    }
    public List<StageSample> stages() {
        return stages.values().stream().sorted(Comparator.comparing((StageSample s) -> s.batchId)
                .thenComparing(s -> s.stage)).toList();
    }
    public Map<String, Object> counters() {
        return Map.of("origin", origin, "phase", phase, "submitted", documents.size(),
                "ready", documents.values().stream().filter(d -> d.readyAt != null).count(),
                "completedBatches", stages.values().stream()
                        .filter(s -> s.stage.equals("KEYWORD_INDEX") && s.completedAt != null).count(),
                "completedChunks", stages.values().stream()
                        .filter(s -> s.stage.equals("KEYWORD_INDEX") && s.completedAt != null).mapToLong(s -> s.chunks).sum());
    }

    public static class DocumentSample {
        public final long documentId;
        public volatile String corpusId;
        public volatile int round;
        public volatile long bytes;
        public volatile Double arrivedAt;
        public volatile Double ackAt;
        public volatile Double readyAt;
        DocumentSample(long id) { documentId = id; }
    }
    public static class StageSample {
        public final String batchId;
        public final String stage;
        public final long documentId;
        public final int chunks;
        public final double enqueuedAt;
        public final double startedAt;
        public volatile Double completedAt;
        StageSample(String id, String stage, long documentId, int chunks, double enqueued, double started) {
            batchId = id;
            this.stage = stage;
            this.documentId = documentId;
            this.chunks = chunks;
            enqueuedAt = enqueued;
            startedAt = started;
        }
    }
}
