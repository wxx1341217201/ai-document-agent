package com.wxx.aidocumentagent.retrieval.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wxx.aidocumentagent.AiDocumentAgentApplication;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import com.wxx.aidocumentagent.keyword.KeywordQuery;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import com.wxx.aidocumentagent.retrieval.RetrievalProperties;
import com.wxx.aidocumentagent.retrieval.RetrievalQuery;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import com.wxx.aidocumentagent.retrieval.rerank.RerankingProperties;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import com.wxx.aidocumentagent.vector.VectorQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Four ablations through production retrieval ports; never invokes ChatModel. */
@Component
@ConditionalOnProperty(prefix = "app.benchmark.retrieval", name = "enabled", havingValue = "true")
public final class RetrievalBenchmarkRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RetrievalBenchmarkRunner.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);
    static final String LATENCY_DEFINITION = "Query Embedding + Vector Search + BM25 Search + RRF Fusion "
            + "+ Cross-Encoder Rerank; wall clock with parallel search; excludes ChatModel and benchmark worker queue";
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;
    private final HybridRetriever hybridRetriever;
    private final RetrievalProperties retrievalProperties;
    private final RerankingProperties rerankingProperties;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final RetrievalBenchmarkCorpusBootstrapper bootstrapper;
    private Map<Long, RetrievalBenchmarkCorpus.Chunk> corpusById = Map.of();
    private BufferedWriter journal;

    public RetrievalBenchmarkRunner(VectorIndex vectorIndex, KeywordIndex keywordIndex, HybridRetriever hybridRetriever,
                                    RetrievalProperties retrievalProperties, RerankingProperties rerankingProperties,
                                    ObjectMapper mapper, Environment environment,
                                    RetrievalBenchmarkCorpusBootstrapper bootstrapper) {
        this.vectorIndex = vectorIndex;
        this.keywordIndex = keywordIndex;
        this.hybridRetriever = hybridRetriever;
        this.retrievalProperties = retrievalProperties;
        this.rerankingProperties = rerankingProperties;
        this.mapper = mapper;
        this.environment = environment;
        this.bootstrapper = bootstrapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        LOG.info("M19A report: {}", benchmark().toAbsolutePath());
    }

    public Path benchmark() throws Exception {
        int warmup = property("warmup", 50), samples = property("samples", 500), concurrency = property("concurrency", 10);
        if (warmup < 50 || samples < 500 || concurrency < 1 || concurrency > 100) {
            throw new IllegalArgumentException("Benchmark requires warmup>=50, samples>=500 and concurrency in 1..100");
        }
        int qualityConcurrency = property("quality-concurrency", 1);
        if (qualityConcurrency < 1 || qualityConcurrency > 100) {
            throw new IllegalArgumentException("quality-concurrency must be in 1..100");
        }
        String profile = environment.getProperty("app.benchmark.retrieval.profile", "EXTERNAL");
        if (!List.of("EXTERNAL", "LOCAL_REAL", "FAKE_FIXED_LATENCY").contains(profile)) {
            throw new IllegalArgumentException("Unknown retrieval benchmark profile");
        }
        Path datasetPath = Path.of(environment.getProperty("app.benchmark.retrieval.dataset",
                "benchmarks/retrieval/retrieval-dataset.jsonl"));
        Path directory = Path.of(environment.getProperty("app.benchmark.retrieval.report-directory", "reports"));
        byte[] labels = Files.readAllBytes(datasetPath);
        RetrievalBenchmarkDataset dataset = RetrievalBenchmarkDataset.load(datasetPath, mapper);
        dataset.requireBenchmarkCoverage();
        RetrievalBenchmarkCorpus corpus = bootstrapper.readCorpus(dataset.knowledgeBaseId());
        corpus.validateLabels(dataset);
        corpusById = corpus.byId();
        String datasetHash = RetrievalBenchmarkSupport.sha256(labels), corpusHash = corpus.fingerprint(mapper);
        String reviewPath = environment.getProperty("app.benchmark.retrieval.label-review", "");
        var review = RetrievalBenchmarkLabelReview.assess(reviewPath.isBlank() ? null : Path.of(reviewPath), dataset,
                datasetHash, corpusHash, mapper);
        boolean hasReranker = rerankingProperties.isEnabled() && rerankingProperties.getUrl() != null;
        if (hasReranker && (environment.getProperty("app.benchmark.retrieval.provider", "").isBlank()
                || environment.getProperty("app.benchmark.retrieval.region", "").isBlank())) {
            throw new IllegalArgumentException("Reranker benchmark requires provider and actual region/routing metadata");
        }
        Files.createDirectories(directory);
        String runId = TIMESTAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        String stem = "retrieval-" + runId;
        Path datasetSnapshot = directory.resolve(stem + "-dataset.jsonl");
        Files.write(datasetSnapshot, labels, StandardOpenOption.CREATE_NEW);
        if (!RetrievalBenchmarkDataset.load(datasetSnapshot, mapper).equals(dataset)) {
            throw new IllegalArgumentException("Dataset changed while freezing labels");
        }
        Path corpusSnapshot = directory.resolve(stem + "-corpus.jsonl");
        StringBuilder source = new StringBuilder();
        corpus.chunks().forEach(chunk -> source.append(mapper.writeValueAsString(chunk)).append('\n'));
        Files.writeString(corpusSnapshot, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve(stem + "-label-review-template.jsonl"),
                RetrievalBenchmarkLabelReview.template(dataset, datasetHash, corpusHash, mapper),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", "m19a-v2");
        report.put("runId", runId);
        report.put("createdAt", Instant.now().toString());
        report.put("status", "RUNNING");
        report.put("dataset", Map.of("path", datasetPath.toString(), "sha256", datasetHash,
                "cases", dataset.cases().size(), "answerable", dataset.cases().stream().filter(c -> c.answerable()).count(),
                "knowledgeBaseId", dataset.knowledgeBaseId(), "snapshot", datasetSnapshot.toString(),
                "annotationProvenance", "assistant_curated_with_source_evidence"));
        report.put("corpus", Map.of("chunks", corpus.chunks().size(), "fingerprint", corpusHash,
                "snapshot", corpusSnapshot.toString()));
        report.put("labelReview", review);
        report.put("configuration", configuration(warmup, samples, concurrency, qualityConcurrency, profile));
        report.put("latencyDefinition", LATENCY_DEFINITION);
        report.put("observations", directory.resolve(stem + "-observations.jsonl").toString());
        Map<String, Object> variants = new LinkedHashMap<>();
        report.put("variants", variants);
        report.put("latency", Map.of("status", "NOT_RUN"));
        report.put("targets", Map.of("status", "NOT_EVALUATED"));
        Path output = directory.resolve(stem + ".json");
        persist(output, report);
        try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve(stem + "-observations.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            journal = writer;
            if (environment.getProperty("app.benchmark.retrieval.bootstrap", Boolean.class, false)) {
                report.put("bootstrap", Map.of("status", "COMPLETE", "indexedChunks", bootstrapper.bootstrap(corpus)));
            }
            else { report.put("bootstrap", Map.of("status", "NOT_REQUESTED")); }
            boolean vectorUnavailable = false, qualityInvalid = false;
            for (Variant variant : Variant.values()) {
                Map<String, Object> result;
                if (variant == Variant.RRF_CROSS_ENCODER && !hasReranker) {
                    result = Map.of("status", "NOT_RUN_MISSING_CROSS_ENCODER");
                }
                else if (vectorUnavailable && (variant == Variant.RRF || variant == Variant.RRF_CROSS_ENCODER)) {
                    result = Map.of("status", "NOT_RUN_VECTOR_UNAVAILABLE");
                }
                else {
                    LOG.info("M19A evaluating {} ({} questions)", variant.label, dataset.cases().size());
                    result = evaluateVariant(variant, dataset, qualityConcurrency);
                }
                variants.put(variant.label, result);
                qualityInvalid |= !"COMPLETE".equals(result.get("status"));
                if (variant == Variant.VECTOR_ONLY && !"COMPLETE".equals(result.get("status"))) { vectorUnavailable = true; }
                persist(output, report);
            }
            report.put("comparison", comparison(variants));
            if (qualityInvalid) { report.put("status", hasReranker ? "INVALID_QUALITY_RUN" : "PARTIAL_MISSING_CROSS_ENCODER"); }
            else {
                LOG.info("M19A latency: warmup={}, samples={}, concurrency={}", warmup, samples, concurrency);
                Map<String, Object> latency = measureLatency(dataset, warmup, samples, concurrency);
                report.put("latency", latency);
                report.put("status", "COMPLETE".equals(latency.get("status")) ? "COMPLETE" : "INVALID_LATENCY_RUN");
                if ("COMPLETE".equals(report.get("status"))) { report.put("targets", targets(variants, latency, profile, review)); }
            }
            String after = bootstrapper.readCorpus(dataset.knowledgeBaseId()).fingerprint(mapper);
            boolean unchanged = corpusHash.equals(after) && datasetHash.equals(
                    RetrievalBenchmarkSupport.sha256(Files.readAllBytes(datasetPath)));
            report.put("evidenceUnchanged", unchanged);
            if (!unchanged) {
                report.put("status", "INVALID_EVIDENCE_CHANGED");
                report.put("targets", Map.of("status", "NOT_EVALUATED"));
            }
        }
        catch (Exception failure) {
            report.put("status", "INVALID_RUN_EXCEPTION");
            report.put("failure", failure.getClass().getSimpleName());
            report.put("targets", Map.of("status", "NOT_EVALUATED"));
            persist(output, report);
            throw failure;
        }
        finally { journal = null; }
        report.put("completedAt", Instant.now().toString());
        persist(output, report);
        return output;
    }

    private int property(String name, int fallback) {
        return environment.getProperty("app.benchmark.retrieval." + name, Integer.class, fallback);
    }

    private Map<String, Object> configuration(int warmup, int samples, int concurrency, int qualityConcurrency, String profile) {
        var config = new LinkedHashMap<String, Object>();
        config.put("profile", profile);
        config.put("chunkSize", environment.getProperty("app.chunking.chunk-size", Integer.class, 900));
        config.put("overlap", environment.getProperty("app.chunking.overlap", Integer.class, 120));
        config.put("chunkingNote", "Pre-existing corpus; changing settings requires rechunking and relabeling");
        config.put("vectorTopK", retrievalProperties.getVectorTopK());
        config.put("keywordTopK", retrievalProperties.getKeywordTopK());
        config.put("rrfK", retrievalProperties.getRrfK());
        config.put("vectorWeight", retrievalProperties.getVectorWeight());
        config.put("keywordWeight", retrievalProperties.getKeywordWeight());
        config.put("vectorSimilarityThreshold", retrievalProperties.getVectorSimilarityThreshold());
        config.put("rerankCandidateLimit", rerankingProperties.getMaxCandidates());
        config.put("rerankProvider", rerankingProperties.getProvider().name());
        config.put("rerankModel", rerankingProperties.getModel());
        config.put("rerankMaxTextLength", rerankingProperties.getMaxTextLength());
        config.put("rerankTopN", 5);
        config.put("finalTopK", 5);
        config.put("warmup", warmup);
        config.put("samples", samples);
        config.put("concurrency", concurrency);
        config.put("qualityConcurrency", qualityConcurrency);
        config.put("samplingSeed", 190019L);
        config.put("percentileMethod", "nearest_rank_successful_samples");
        for (String field : List.of("provider", "region", "deployment", "embedding-provider", "embedding-region")) {
            config.put(field, environment.getProperty("app.benchmark.retrieval." + field, "UNSET"));
        }
        config.put("embeddingModel", environment.getProperty("app.vector.embedding.model", "UNSET"));
        config.put("embeddingDimensions", environment.getProperty("app.vector.embedding.dimensions", Integer.class, 1024));
        config.put("embeddingTransport", "Benchmark JDK HttpClient HTTP/1.1; same endpoint/model; no cache; retries=0");
        config.put("embeddingTimeout", environment.getProperty("app.vector.embedding.timeout", "20s"));
        config.put("embeddingBatchSize", environment.getProperty("app.vector.embedding.batch-size", Integer.class, 10));
        config.put("vectorCollection", environment.getProperty("app.vector.qdrant.collection-name", "UNSET"));
        config.put("keywordAlias", environment.getProperty("app.keyword.alias", "UNSET"));
        config.put("vectorTimeout", retrievalProperties.getVectorTimeout().toString());
        config.put("keywordTimeout", retrievalProperties.getKeywordTimeout().toString());
        config.put("searchTotalTimeout", retrievalProperties.getTotalTimeout().toString());
        config.put("rerankReadTimeout", rerankingProperties.getReadTimeout().toString());
        config.put("rerankMaxRetries", rerankingProperties.getMaxRetries());
        config.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        config.put("java", System.getProperty("java.version"));
        config.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        String metadata = environment.getProperty("app.benchmark.retrieval.model-metadata", "");
        if (!metadata.isBlank()) {
            try { config.put("localModel", mapper.readValue(Files.readString(Path.of(metadata)), Map.class)); }
            catch (IOException failure) { throw new IllegalArgumentException("Cannot read local model metadata", failure); }
        }
        return config;
    }

    private Map<String, Object> evaluateVariant(Variant variant, RetrievalBenchmarkDataset dataset, int concurrency) throws Exception {
        var observations = new ArrayList<QualityObservation>();
        if (concurrency == 1) {
            int consecutiveErrors = 0;
            for (var item : dataset.cases()) {
                var observed = consecutiveErrors >= 3
                        ? new QualityObservation(item.id(), List.of(), "SKIPPED_AFTER_CONSECUTIVE_FAILURES") : observe(variant, item);
                consecutiveErrors = observed.errorCode() == null ? 0 : consecutiveErrors + 1;
                observations.add(observed);
                record(Map.of("phase", "QUALITY", "variant", variant.label, "observation", observed));
            }
        }
        else {
            try (ExecutorService workers = Executors.newFixedThreadPool(concurrency)) {
                var futures = new ArrayList<Future<QualityObservation>>();
                for (var item : dataset.cases()) {
                    futures.add(workers.submit(() -> {
                        var observed = observe(variant, item);
                        record(Map.of("phase", "QUALITY", "variant", variant.label, "observation", observed));
                        return observed;
                    }));
                }
                for (var future : futures) { observations.add(future.get()); }
            }
        }
        Map<String, List<Long>> predictions = new LinkedHashMap<>();
        List<Map<String, String>> errors = new ArrayList<>();
        observations.forEach(observed -> {
            predictions.put(observed.caseId(), observed.top5());
            if (observed.errorCode() != null) { errors.add(Map.of("caseId", observed.caseId(), "code", observed.errorCode())); }
        });
        long nonempty = dataset.cases().stream().filter(c -> c.answerable()).filter(item -> !predictions.get(item.id()).isEmpty()).count();
        return Map.of("status", !errors.isEmpty() ? "INVALID_REQUEST_ERRORS" : nonempty == 0 ? "INVALID_EMPTY_INDEX_OR_RESULTS" : "COMPLETE",
                "nonemptyAnswerableResults", nonempty, "byTag", RetrievalBenchmarkMetrics.score(dataset.cases(), predictions),
                "errors", errors, "top5ByCase", predictions);
    }

    private QualityObservation observe(Variant variant, RetrievalBenchmarkDataset.Case item) {
        try { return new QualityObservation(item.id(), predict(variant, item), null); }
        catch (RuntimeException failure) { return new QualityObservation(item.id(), List.of(), safeCode(failure)); }
    }

    private static String safeCode(RuntimeException failure) {
        if (failure instanceof VectorIndexException vectorFailure) { return vectorFailure.getErrorCode().code(); }
        if (failure instanceof KeywordIndexException keywordFailure) { return keywordFailure.getErrorCode().code(); }
        return failure instanceof InvalidEvidence ? "RETRIEVAL_EVIDENCE_MISMATCH" : "RETRIEVAL_FAILED_OR_DEGRADED";
    }

    private List<Long> predict(Variant variant, RetrievalBenchmarkDataset.Case item) {
        return switch (variant) {
            case VECTOR_ONLY -> {
                var hits = vectorIndex.search(new VectorQuery(item.knowledgeBaseId(), item.question(),
                        retrievalProperties.getVectorTopK(), retrievalProperties.getVectorSimilarityThreshold()));
                var ids = new ArrayList<Long>();
                for (var hit : hits) {
                    checkEvidence(hit.chunkId(), hit.knowledgeBaseId(), hit.documentId(), hit.content());
                    ids.add(hit.chunkId());
                }
                yield ids.stream().distinct().limit(5).toList();
            }
            case BM25_ONLY -> {
                var hits = keywordIndex.search(new KeywordQuery(item.knowledgeBaseId(), item.question(), retrievalProperties.getKeywordTopK()));
                var ids = new ArrayList<Long>();
                for (var hit : hits) {
                    checkEvidence(hit.chunkId(), hit.knowledgeBaseId(), hit.documentId(), hit.content());
                    ids.add(hit.chunkId());
                }
                yield ids.stream().distinct().limit(5).toList();
            }
            case RRF, RRF_CROSS_ENCODER -> {
                var result = hybridRetriever.retrieve(query(item), variant == Variant.RRF_CROSS_ENCODER);
                requireComplete(result, variant == Variant.RRF_CROSS_ENCODER, true);
                yield checkedIds(result.chunks());
            }
        };
    }

    private RetrievalQuery query(RetrievalBenchmarkDataset.Case item) {
        return new RetrievalQuery(item.knowledgeBaseId(), item.question(), retrievalProperties.getVectorTopK(), retrievalProperties.getKeywordTopK(), 5);
    }

    private void checkEvidence(long chunkId, long knowledgeBaseId, long documentId, String content) {
        var expected = corpusById.get(chunkId);
        if (expected == null || expected.knowledgeBaseId() != knowledgeBaseId || expected.documentId() != documentId
                || !expected.content().equals(content)) { throw new InvalidEvidence(); }
    }

    private List<Long> checkedIds(List<RetrievedChunk> chunks) {
        chunks.forEach(chunk -> checkEvidence(chunk.chunkId(), chunk.knowledgeBaseId(), chunk.documentId(), chunk.content()));
        return chunks.stream().map(RetrievedChunk::chunkId).distinct().limit(5).toList();
    }

    private static void requireComplete(RetrievalResult result, boolean rerank, boolean allowEmpty) {
        if (result.degraded() || result.rerankingDegraded()
                || (rerank && !result.rerankingApplied() && (!allowEmpty || !result.chunks().isEmpty()))) {
            throw new IllegalStateException("Full retrieval path did not complete");
        }
    }

    private Map<String, Object> measureLatency(RetrievalBenchmarkDataset dataset, int warmup, int samples, int concurrency) throws Exception {
        var queries = new ArrayList<>(dataset.cases().stream().filter(c -> c.answerable()).toList());
        Collections.shuffle(queries, new Random(190019L));
        int warmupErrors = 0;
        for (int index = 0; index < warmup; index++) {
            var sample = timedRetrieval("WARMUP", index, queries.get(index % queries.size()));
            record(sample);
            if (sample.errorCode() != null) { warmupErrors++; }
        }
        if (warmupErrors > 0) {
            return Map.of("status", "INVALID_WARMUP", "warmup", warmup, "warmupErrors", warmupErrors, "samples", 0,
                    "requestedSamples", samples, "errors", 0, "errorRate", 0D, "concurrency", concurrency);
        }
        var measured = new ArrayList<LatencySample>();
        try (ExecutorService workers = Executors.newFixedThreadPool(concurrency)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<LatencySample>>();
            for (int index = 0; index < samples; index++) {
                int ordinal = index;
                var item = queries.get(index % queries.size());
                tasks.add(() -> {
                    var sample = timedRetrieval("SAMPLE", ordinal, item);
                    record(sample);
                    return sample;
                });
            }
            int deadline = property("sampling-deadline-seconds", 900);
            if (deadline < 1) { throw new IllegalArgumentException("Sampling deadline must be positive"); }
            var futures = workers.invokeAll(tasks, deadline, TimeUnit.SECONDS);
            for (int index = 0; index < futures.size(); index++) {
                var future = futures.get(index);
                if (future.isCancelled()) {
                    var sample = new LatencySample("SAMPLE", index, queries.get(index % queries.size()).id(), null, null, null, "SAMPLE_DEADLINE_EXCEEDED");
                    measured.add(sample);
                    record(sample);
                }
                else { measured.add(future.get()); }
            }
        }
        List<Long> durations = measured.stream().filter(sample -> sample.errorCode() == null).map(LatencySample::durationNanos).toList();
        int errors = measured.size() - durations.size();
        var result = new LinkedHashMap<String, Object>();
        result.put("status", errors == 0 && measured.size() == samples ? "COMPLETE" : "INVALID_REQUEST_ERRORS");
        result.put("warmup", warmup);
        result.put("warmupErrors", warmupErrors);
        result.put("samples", measured.size());
        result.put("requestedSamples", samples);
        result.put("successes", durations.size());
        result.put("errors", errors);
        result.put("errorRate", errors / (double) samples);
        result.put("concurrency", concurrency);
        result.put("unit", "ms");
        result.put("statistics", RetrievalBenchmarkMetrics.latencyMillis(durations));
        result.put("rerankStatistics", RetrievalBenchmarkMetrics.latencyMillis(measured.stream().filter(sample -> sample.errorCode() == null).map(LatencySample::rerankNanos).toList()));
        result.put("sampleRecords", measured);
        return result;
    }

    private LatencySample timedRetrieval(String phase, int ordinal, RetrievalBenchmarkDataset.Case item) {
        String startedAt = Instant.now().toString();
        long start = System.nanoTime(); // Starts on a worker, after benchmark queue wait.
        try {
            var result = hybridRetriever.retrieve(query(item), true);
            long elapsed = System.nanoTime() - start;
            requireComplete(result, true, false);
            checkedIds(result.chunks());
            return new LatencySample(phase, ordinal, item.id(), startedAt, elapsed, result.rerankingElapsed().toNanos(), null);
        }
        catch (RuntimeException failure) {
            return new LatencySample(phase, ordinal, item.id(), startedAt, System.nanoTime() - start, null, safeCode(failure));
        }
    }

    private synchronized void record(Object observation) throws IOException {
        journal.write(mapper.writeValueAsString(observation));
        journal.newLine();
        journal.flush();
    }

    private Map<String, Object> comparison(Map<String, Object> variants) {
        var vector = scores(variants, Variant.VECTOR_ONLY);
        var rrf = scores(variants, Variant.RRF);
        var reranked = scores(variants, Variant.RRF_CROSS_ENCODER);
        if (vector == null || rrf == null || reranked == null) { return Map.of("status", "NOT_EVALUATED"); }
        var buckets = new LinkedHashMap<String, Object>();
        for (String tag : reranked.keySet()) {
            var after = reranked.get(tag);
            if (after.answerable() == 0) { continue; }
            buckets.put(tag, Map.of("recallDeltaVsVector", after.recallAt5() - vector.get(tag).recallAt5(),
                    "recallDeltaVsRrf", after.recallAt5() - rrf.get(tag).recallAt5(), "mrrDeltaVsRrf", after.mrrAt5() - rrf.get(tag).mrrAt5(),
                    "ndcgDeltaVsRrf", after.ndcgAt5() - rrf.get(tag).ndcgAt5()));
        }
        return Map.of("status", "COMPLETE", "byTag", buckets);
    }

    @SuppressWarnings("unchecked")
    static Map<String, RetrievalBenchmarkMetrics.Score> scores(Map<String, Object> variants, Variant variant) {
        Map<String, Object> result = (Map<String, Object>) variants.get(variant.label);
        return result != null && "COMPLETE".equals(result.get("status")) ? (Map<String, RetrievalBenchmarkMetrics.Score>) result.get("byTag") : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> targets(Map<String, Object> variants, Map<String, Object> latency, String profile, Map<String, Object> review) {
        double recall = scores(variants, Variant.RRF_CROSS_ENCODER).get("all").recallAt5();
        double p95 = ((Map<String, Double>) latency.get("statistics")).get("p95");
        boolean eligible = !"FAKE_FIXED_LATENCY".equals(profile) && "HUMAN_REVIEWED".equals(review.get("status"));
        String status = "FAKE_FIXED_LATENCY".equals(profile) ? "REGRESSION_ONLY" : !eligible ? "PROVISIONAL_PENDING_HUMAN_LABEL_REVIEW"
                : recall >= 0.91 && p95 <= 490 ? "MET" : "NOT_MET";
        return Map.of("status", status, "claimEligible", eligible, "recallAt5", recall, "recallThreshold", 0.91,
                "recallMet", recall >= 0.91, "p95Ms", p95, "p95ThresholdMs", 490, "p95Met", p95 <= 490);
    }

    private void persist(Path output, Map<String, Object> report) throws IOException {
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(output.resolveSibling(output.getFileName().toString().replace(".json", ".md")), RetrievalBenchmarkReportWriter.markdown(report), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        if (List.of(args).contains("--validate-only")) {
            var options = new java.util.HashMap<String, String>();
            for (String argument : args) {
                String[] parts = argument.split("=", 2);
                if (parts.length == 2) { options.put(parts[0], parts[1]); }
            }
            try {
                var mapper = new tools.jackson.databind.json.JsonMapper();
                Path path = Path.of(options.getOrDefault("--dataset", "benchmarks/retrieval/retrieval-dataset.jsonl"));
                var dataset = RetrievalBenchmarkDataset.load(path, mapper);
                dataset.requireBenchmarkCoverage();
                if (options.containsKey("--corpus")) {
                    RetrievalBenchmarkCorpus.load(Path.of(options.get("--corpus")), mapper).validateLabels(dataset);
                }
                System.out.println("M19A dataset VALID: " + dataset.cases().size() + " cases; sha256="
                        + RetrievalBenchmarkSupport.sha256(Files.readAllBytes(path)));
                return;
            }
            catch (IOException failure) { throw new IllegalArgumentException("Cannot validate retrieval dataset", failure); }
        }
        SpringApplication application = new SpringApplication(AiDocumentAgentApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        var arguments = new ArrayList<>(List.of(args));
        var forced = List.of("--app.benchmark.retrieval.enabled=", "--app.benchmark.rag.enabled=", "--app.benchmark.agent.enabled=",
                "--spring.flyway.enabled=", "--app.ingestion.enabled=", "--app.agent.execution.enabled=", "--app.progress.enabled=",
                "--spring.rabbitmq.listener.simple.auto-startup=");
        arguments.removeIf(argument -> forced.stream().anyMatch(argument::startsWith));
        arguments.addAll(List.of("--app.benchmark.retrieval.enabled=true", "--app.benchmark.rag.enabled=false", "--app.benchmark.agent.enabled=false",
                "--spring.flyway.enabled=false", "--app.ingestion.enabled=false", "--app.agent.execution.enabled=false", "--app.progress.enabled=false",
                "--spring.rabbitmq.listener.simple.auto-startup=false"));
        try (var context = application.run(arguments.toArray(String[]::new))) { LOG.info("M19A benchmark complete; closing context"); }
    }

    record QualityObservation(String caseId, List<Long> top5, String errorCode) { }
    record LatencySample(String phase, int ordinal, String caseId, String startedAt, Long durationNanos, Long rerankNanos, String errorCode) { }
    private static final class InvalidEvidence extends RuntimeException { }
    enum Variant {
        VECTOR_ONLY("Vector Only"), BM25_ONLY("BM25 Only"), RRF("Vector + BM25 + RRF"), RRF_CROSS_ENCODER("Vector + BM25 + RRF + Cross-Encoder");
        final String label;
        Variant(String label) { this.label = label; }
    }
}
