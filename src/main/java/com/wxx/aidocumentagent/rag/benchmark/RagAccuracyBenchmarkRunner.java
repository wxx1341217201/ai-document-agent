package com.wxx.aidocumentagent.rag.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.wxx.aidocumentagent.AiDocumentAgentApplication;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryCommand;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset.Sample;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Observation;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Review;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyReport.CaseResult;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyReport.DatasetInfo;
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
import tools.jackson.databind.json.JsonMapper;

/** M19B opt-in：复用生产 RAG 应用服务，逐题生成答案；人工复核离线完成，不重新调用模型。 */
@Component
@ConditionalOnProperty(prefix = "app.benchmark.rag", name = "enabled", havingValue = "true")
public final class RagAccuracyBenchmarkRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagAccuracyBenchmarkRunner.class);
    private final RagQueryApplicationService rag;
    private final DocumentChunkRepository chunks;
    private final RagProperties properties;
    private final Environment environment;
    private final ObjectMapper mapper;

    public RagAccuracyBenchmarkRunner(RagQueryApplicationService rag, DocumentChunkRepository chunks,
                                      RagProperties properties, Environment environment, ObjectMapper mapper) {
        this.rag = rag;
        this.chunks = chunks;
        this.properties = properties;
        this.environment = environment;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path dataset = Path.of(environment.getProperty("app.benchmark.rag.dataset", "benchmarks/rag/qa-dataset.jsonl"));
        Path reports = Path.of(environment.getProperty("app.benchmark.rag.report-directory", "reports"));
        Path report = benchmark(dataset, reports);
        log.info("M19B report: {} (human review required before accuracy claims)", report.toAbsolutePath());
    }

    public Path benchmark(Path datasetPath, Path reportDirectory) throws IOException {
        RagAccuracyDataset dataset = RagAccuracyDataset.load(datasetPath, mapper);
        dataset.requireBenchmarkCoverage();
        String runId = UUID.randomUUID().toString();
        Path snapshot = snapshotDataset(datasetPath, reportDirectory, runId, dataset, mapper);
        String datasetSha = BenchmarkText.sha256(Files.readAllBytes(snapshot));
        String createdAt = Instant.now().toString();
        int topK = environment.getProperty("app.benchmark.rag.top-k", Integer.class, properties.getDefaultTopK());
        int maxErrors = environment.getProperty("app.benchmark.rag.max-consecutive-errors", Integer.class, 3);
        int maxDegraded = environment.getProperty("app.benchmark.rag.max-consecutive-degraded", Integer.class, 3);
        boolean rerank = environment.getProperty("app.benchmark.rag.rerank", Boolean.class, true);
        if (topK < 1 || topK > RagProperties.MAX_TOP_K || maxErrors < 1 || maxErrors > 10 || maxDegraded < 1 || maxDegraded > 10) {
            throw new IllegalArgumentException("Invalid benchmark top-k or max-consecutive-errors");
        }
        Map<String, Object> configuration = configuration(topK, rerank, maxErrors);
        configuration.put("maxConsecutiveDegraded", maxDegraded);
        configuration.put("datasetSnapshotPath", snapshot.toAbsolutePath().toString());
        List<String> limitations = new ArrayList<>(List.of(
                "Draft labels require human verification against source text; lexical fact matches are preliminary.",
                "No LLM judge is used. Human review assesses contradictions, claim grounding and refusal correctness.",
                "Multi-turn samples use fixed visible history with RagQueryApplicationService, without conversation writes."));
        String fingerprint = null;
        RagAccuracyCorpus corpus = null;
        String preflightFailure = null;
        try {
            corpus = captureCorpus(dataset.knowledgeBaseId());
            corpus.validateLabels(dataset);
            Path corpusPath = reportDirectory.resolve("rag-" + runId + "-corpus.jsonl");
            corpus.write(corpusPath, mapper);
            fingerprint = corpus.fingerprint(mapper);
            configuration.put("corpusSnapshotPath", corpusPath.toAbsolutePath().toString());
            configuration.put("corpusSha256", BenchmarkText.sha256(Files.readAllBytes(corpusPath)));
            configuration.put("corpusChunks", corpus.chunks().size());
        }
        catch (RuntimeException exception) {
            preflightFailure = "CORPUS_PREFLIGHT_FAILED:" + safeError(exception);
            limitations.add(preflightFailure);
        }
        RagAccuracyEvaluator evaluator = new RagAccuracyEvaluator(mapper);
        List<CaseResult> results = new ArrayList<>();
        int consecutiveErrors = 0;
        int consecutiveDegraded = 0;
        Path journal = reportDirectory.resolve("rag-" + runId + "-observations.jsonl");
        Files.createDirectories(reportDirectory);
        Files.createFile(journal);
        boolean preflightOnly = environment.getProperty("app.benchmark.rag.preflight-only", Boolean.class, false);
        if (preflightOnly) {
            configuration.put("mode", "PREFLIGHT_ONLY");
        }
        for (Sample sample : dataset.samples()) {
            Observation observation;
            if (preflightFailure != null || preflightOnly || consecutiveErrors >= maxErrors || consecutiveDegraded >= maxDegraded) {
                observation = Observation.notRun(preflightFailure != null ? preflightFailure
                        : preflightOnly ? "CORPUS_PREFLIGHT_ONLY" : consecutiveErrors >= maxErrors
                        ? "STOPPED_AFTER_CONSECUTIVE_ERRORS" : "STOPPED_AFTER_CONSECUTIVE_DEGRADED_REQUESTS");
            }
            else {
                observation = observe(sample, topK, rerank, corpus);
                consecutiveErrors = observation.errorCode() == null ? 0 : consecutiveErrors + 1;
                consecutiveDegraded = observation.degraded() ? consecutiveDegraded + 1 : 0;
            }
            String hash = evaluator.resultHash(runId, datasetSha, sample, observation);
            CaseResult result = new CaseResult(sample, observation, hash,
                    evaluator.evaluate(runId, datasetSha, sample, observation, null));
            results.add(result);
            // 每题落盘，便于中断后诊断；这里只保存用户可见答案、证据和错误类型。
            Files.writeString(journal, mapper.writeValueAsString(result) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            if (results.size() % 10 == 0) {
                log.info("M19B progress: {}/{}; consecutive errors={}", results.size(), dataset.samples().size(), consecutiveErrors);
            }
        }
        boolean corpusStable = false;
        if (fingerprint != null) {
            try {
                corpusStable = fingerprint.equals(captureCorpus(dataset.knowledgeBaseId()).fingerprint(mapper));
                if (!corpusStable) {
                    limitations.add("CORPUS_CHANGED_DURING_RUN");
                }
            }
            catch (RuntimeException exception) {
                limitations.add("CORPUS_FINAL_CHECK_FAILED:" + safeError(exception));
            }
        }
        configuration.put("corpusStable", corpusStable);
        DatasetInfo info = new DatasetInfo(datasetPath.toAbsolutePath().toString(), datasetSha,
                dataset.knowledgeBaseId(), dataset.samples().size(), provenance(dataset), fingerprint);
        RagAccuracyReport report = RagAccuracyReport.build(runId, createdAt, info, configuration, limitations, results);
        return new RagAccuracyReportWriter(mapper).write(reportDirectory, report);
    }

    private Observation observe(Sample sample, int topK, boolean rerank, RagAccuracyCorpus corpus) {
        long start = System.nanoTime();
        try {
            var execution = rag.queryForConversation(sample.knowledgeBaseId(),
                    new RagQueryCommand(sample.question(), topK, rerank), sample.history());
            var response = execution.response();
            String evidenceFailure = null;
            try {
                corpus.verifyEvidence(sample.knowledgeBaseId(), execution.evidence());
                verifyEvidence(sample, execution.evidence());
            }
            catch (RuntimeException exception) {
                evidenceFailure = safeError(exception);
            }
            return new Observation(true, response.answer(), response.citations(), execution.evidence(),
                    execution.promptSha256(), execution.completion() == null ? null : execution.completion().modelName(),
                    execution.completion() != null, response.retrieval().degraded(), elapsed(start), evidenceFailure,
                    execution.retrievalTrace());
        }
        catch (RuntimeException exception) {
            return new Observation(true, null, List.of(), List.of(), null, null, null, false,
                    elapsed(start), safeError(exception));
        }
    }

    private void verifyEvidence(Sample sample, List<com.wxx.aidocumentagent.rag.application.RagEvidenceChunk> evidence) {
        if (evidence.isEmpty()) {
            return;
        }
        List<Long> ids = evidence.stream().map(item -> item.citation().chunkId()).toList();
        Map<Long, DocumentChunk> persisted = scopedChunks(sample.knowledgeBaseId(), ids);
        for (var item : evidence) {
            DocumentChunk source = persisted.get(item.citation().chunkId());
            if (item.knowledgeBaseId() != sample.knowledgeBaseId() || source == null
                    || source.getDocumentId() != item.citation().documentId()
                    || !BenchmarkText.sha256(source.getContent()).equals(item.sourceContentSha256())
                    || !source.getContent().startsWith(item.contextText())
                    || item.citation().quote() == null || !source.getContent().startsWith(item.citation().quote())
                    || !Objects.equals(source.getPageFrom(), item.citation().pageFrom())
                    || !Objects.equals(source.getPageTo(), item.citation().pageTo())) {
                throw new IllegalArgumentException("UNVERIFIED_CURRENT_CONTEXT");
            }
        }
    }

    private RagAccuracyCorpus captureCorpus(long knowledgeBaseId) {
        return RagAccuracyCorpus.capture(knowledgeBaseId, chunks.findByKnowledgeBaseIdOrderByIdAsc(knowledgeBaseId));
    }

    private Map<Long, DocumentChunk> scopedChunks(long knowledgeBaseId, java.util.Collection<Long> ids) {
        Map<Long, DocumentChunk> result = new HashMap<>();
        for (DocumentChunk chunk : chunks.findByKnowledgeBaseIdAndIdIn(knowledgeBaseId, ids)) {
            if (chunk.getKnowledgeBaseId() == null || chunk.getKnowledgeBaseId() != knowledgeBaseId
                    || chunk.getId() == null || !ids.contains(chunk.getId()) || chunk.getContent() == null) {
                throw new IllegalArgumentException("FOREIGN_OR_INVALID_PERSISTED_CHUNK");
            }
            result.put(chunk.getId(), chunk);
        }
        return result;
    }

    private Map<String, Object> configuration(int topK, boolean rerank, int maxErrors) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "LIVE");
        config.put("pipeline", "RagQueryApplicationService.queryForConversation");
        config.put("model", properties.getModel());
        config.put("temperature", environment.getProperty("spring.ai.openai.chat.temperature", Double.class, 0.0));
        config.put("topK", topK);
        config.put("vectorTopK", Math.max(topK, environment.getProperty("app.retrieval.vector-top-k", Integer.class, 20)));
        config.put("keywordTopK", Math.max(topK, environment.getProperty("app.retrieval.keyword-top-k", Integer.class, 20)));
        config.put("finalTopK", topK);
        config.put("rerankRequested", rerank);
        config.put("rerankEnabled", environment.getProperty("app.rerank.enabled", Boolean.class, false));
        config.put("rerankProvider", environment.getProperty("app.rerank.provider", "generic"));
        config.put("rerankModel", environment.getProperty("app.rerank.model", ""));
        config.put("deployment", environment.getProperty("app.benchmark.rag.deployment", "unspecified"));
        config.put("concurrency", 1);
        config.put("maxConsecutiveErrors", maxErrors);
        config.put("contextWindowTokens", properties.getContextWindowTokens());
        config.put("reservedOutputTokens", properties.getReservedOutputTokens());
        config.put("maxContextCharacters", properties.getMaxContextCharacters());
        config.put("maxChunkCharacters", properties.getMaxChunkCharacters());
        config.put("chatTimeout", properties.getChatTimeout().toString());
        config.put("chatBaseUrl", environment.getProperty("spring.ai.openai.chat.base-url", "unspecified"));
        config.put("embeddingBaseUrl", environment.getProperty("spring.ai.openai.embedding.base-url", "unspecified"));
        config.put("embeddingTransport", "SpringAiOpenAiEmbeddingModel");
        config.put("generatorImplementationVersion", "m19b-quality-iteration1");
        config.put("queryExpansionEnabled", properties.isQueryExpansionEnabled());
        config.put("maxRetrievalQueries", properties.getMaxRetrievalQueries());
        config.put("queryExpansionTimeout", properties.getQueryExpansionTimeout().toString());
        config.put("queryExpansionVersion", com.wxx.aidocumentagent.rag.application.RagRetrievalQueryPlanner.VERSION);
        for (String name : List.of("app.chunking.chunk-size", "app.chunking.overlap", "app.retrieval.rrf-k",
                "app.retrieval.vector-timeout", "app.retrieval.keyword-timeout", "app.retrieval.total-timeout",
                "app.retrieval.vector-similarity-threshold", "app.retrieval.vector-weight", "app.retrieval.keyword-weight",
                "app.vector.embedding.model", "app.vector.embedding.dimensions", "app.vector.embedding.timeout",
                "app.vector.embedding.max-retries", "app.vector.qdrant.collection-name", "app.keyword.index-name", "app.keyword.alias",
                "app.keyword.initialize-schema",
                "app.rerank.url", "app.rerank.max-candidates", "app.rerank.max-text-length", "app.rerank.connect-timeout",
                "app.rerank.read-timeout", "app.rerank.max-retries", "app.rerank.circuit-failure-threshold",
                "app.rerank.circuit-open-duration")) {
            config.put(name, environment.getProperty(name, "unspecified"));
        }
        return config;
    }

    private String safeError(RuntimeException exception) {
        return exception instanceof BusinessException business ? business.getErrorCode().code()
                : exception instanceof IllegalArgumentException && exception.getMessage() != null
                && exception.getMessage().matches("[A-Z_]+") ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private double elapsed(long start) {
        return Math.max(0, System.nanoTime() - start) / 1_000_000.0;
    }

    private static String provenance(RagAccuracyDataset dataset) {
        return dataset.samples().stream().map(Sample::labelProvenance).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** 无 Spring/数据库依赖的标签校验和人工复核入口。 */
    public static void main(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        for (String argument : args) {
            String[] parts = argument.split("=", 2);
            options.put(parts[0], parts.length == 1 ? "true" : parts[1]);
        }
        if (options.containsKey("--live")) {
            SpringApplication application = new SpringApplication(AiDocumentAgentApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            List<String> springArgs = new ArrayList<>(List.of(args));
            springArgs.remove("--live");
            List<String> forced = List.of("--app.benchmark.rag.enabled=", "--app.benchmark.retrieval.enabled=",
                    "--spring.flyway.enabled=", "--app.ingestion.enabled=", "--app.progress.enabled=",
                    "--app.agent.execution.enabled=", "--spring.rabbitmq.listener.simple.auto-startup=",
                    "--app.vector.qdrant.initialization-strategy=", "--app.keyword.initialize-schema=");
            springArgs.removeIf(argument -> forced.stream().anyMatch(argument::startsWith));
            springArgs.add("--app.benchmark.rag.enabled=true");
            springArgs.add("--app.benchmark.retrieval.enabled=false");
            springArgs.add("--spring.flyway.enabled=false");
            springArgs.add("--app.ingestion.enabled=false");
            springArgs.add("--app.progress.enabled=false");
            springArgs.add("--app.agent.execution.enabled=false");
            springArgs.add("--spring.rabbitmq.listener.simple.auto-startup=false");
            springArgs.add("--app.vector.qdrant.initialization-strategy=VALIDATE_EXISTING");
            springArgs.add("--app.keyword.initialize-schema=false");
            try (var context = application.run(springArgs.toArray(String[]::new))) {
                // ApplicationRunner 已完成报告；关闭上下文以退出独立 benchmark 进程。
                log.info("M19B benchmark context closing");
            }
            return;
        }
        ObjectMapper mapper = JsonMapper.builder().build();
        Path reports = Path.of(options.getOrDefault("--report-directory", "reports"));
        RagAccuracyReport report;
        if (options.containsKey("--review-report")) {
            report = applyReviews(Path.of(options.get("--review-report")),
                    Path.of(Objects.requireNonNull(options.get("--reviews"), "--reviews is required")), mapper);
        }
        else if (options.containsKey("--validate-only")) {
            Path path = Path.of(options.getOrDefault("--dataset", "benchmarks/rag/qa-dataset.jsonl"));
            RagAccuracyDataset dataset = RagAccuracyDataset.load(path, mapper);
            dataset.requireBenchmarkCoverage();
            String runId = UUID.randomUUID().toString();
            Path snapshot = snapshotDataset(path, reports, runId, dataset, mapper);
            String hash = BenchmarkText.sha256(Files.readAllBytes(snapshot));
            RagAccuracyEvaluator evaluator = new RagAccuracyEvaluator(mapper);
            List<CaseResult> results = dataset.samples().stream().map(sample -> {
                Observation observation = Observation.notRun("DATASET_VALIDATION_ONLY");
                return new CaseResult(sample, observation, evaluator.resultHash(runId, hash, sample, observation),
                        evaluator.evaluate(runId, hash, sample, observation, null));
            }).toList();
            report = RagAccuracyReport.build(runId, Instant.now().toString(), new DatasetInfo(path.toAbsolutePath().toString(),
                    hash, dataset.knowledgeBaseId(), dataset.samples().size(), provenance(dataset), null),
                    Map.of("mode", "VALIDATE_ONLY", "datasetSnapshotPath", snapshot.toAbsolutePath().toString()),
                    List.of("Only dataset structure and coverage were validated. No database, retrieval or ChatModel call was made."), results);
        }
        else {
            throw new IllegalArgumentException("Use --validate-only or --review-report=<json> --reviews=<jsonl>");
        }
        Path output = new RagAccuracyReportWriter(mapper).write(reports, report);
        System.out.println("M19B report: " + output.toAbsolutePath());
        System.out.println("QA Accuracy=" + report.overall().qaAccuracy() + "; target=" + report.targetStatus());
    }

    public static RagAccuracyReport applyReviews(Path reportPath, Path reviewPath, ObjectMapper mapper) throws IOException {
        RagAccuracyReport original = mapper.readValue(Files.readString(reportPath, StandardCharsets.UTF_8), RagAccuracyReport.class);
        if (!original.schemaVersion().equals("m19b-report-v1")
                || !original.policyVersion().equals(RagAccuracyEvaluator.POLICY_VERSION)) {
            throw new IllegalArgumentException("Unsupported report or policy version");
        }
        Path datasetPath = resolveSnapshot(reportPath, String.valueOf(original.configuration()
                .getOrDefault("datasetSnapshotPath", original.dataset().path())));
        if (!BenchmarkText.sha256(Files.readAllBytes(datasetPath)).equals(original.dataset().sha256())) {
            throw new IllegalArgumentException("Dataset changed after generation; use the original dataset snapshot");
        }
        RagAccuracyDataset dataset = RagAccuracyDataset.load(datasetPath, mapper);
        dataset.requireBenchmarkCoverage();
        if (original.cases().size() != dataset.samples().size()
                || original.dataset().samples() != dataset.samples().size()
                || original.dataset().knowledgeBaseId() != dataset.knowledgeBaseId()) {
            throw new IllegalArgumentException("Report must retain the entire original dataset denominator");
        }
        RagAccuracyCorpus corpus = null;
        if (original.configuration().containsKey("corpusSnapshotPath")) {
            Path corpusPath = resolveSnapshot(reportPath, String.valueOf(original.configuration().get("corpusSnapshotPath")));
            if (!BenchmarkText.sha256(Files.readAllBytes(corpusPath)).equals(original.configuration().get("corpusSha256"))) {
                throw new IllegalArgumentException("Corpus snapshot changed after generation");
            }
            corpus = RagAccuracyCorpus.load(corpusPath, dataset.knowledgeBaseId(), mapper);
            corpus.validateLabels(dataset);
            if (!corpus.fingerprint(mapper).equals(original.dataset().referencedChunkFingerprint())) {
                throw new IllegalArgumentException("Corpus fingerprint differs from the original run");
            }
        }
        else if (original.cases().stream().anyMatch(item -> item.observation().executed() && item.observation().errorCode() == null)) {
            throw new IllegalArgumentException("Executed report requires the frozen corpus for offline review");
        }
        Map<String, Sample> labels = new HashMap<>();
        dataset.samples().forEach(sample -> labels.put(sample.id(), sample));
        Set<String> uniqueCases = new HashSet<>();
        for (var item : original.cases()) {
            if (!uniqueCases.add(item.sample().id()) || !item.sample().equals(labels.get(item.sample().id()))) {
                throw new IllegalArgumentException("Report labels differ from the original dataset");
            }
        }
        Map<String, Review> reviews = new HashMap<>();
        Set<String> reviewedCaseIds = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        original.cases().forEach(item -> caseIds.add(item.sample().id()));
        for (String line : Files.readAllLines(reviewPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Review review = mapper.readValue(line, Review.class);
            if (review == null || !caseIds.contains(review.caseId()) || !reviewedCaseIds.add(review.caseId())) {
                throw new IllegalArgumentException("Unknown or duplicate human review case");
            }
            var item = original.cases().stream().filter(result -> result.sample().id().equals(review.caseId())).findFirst().orElseThrow();
            if (!Objects.equals(review.runId(), original.runId()) || !Objects.equals(review.datasetSha256(), original.dataset().sha256())
                    || !Objects.equals(review.resultSha256(), item.resultSha256())) {
                throw new IllegalArgumentException("Unbound human review template: " + review.caseId());
            }
            if (review.reviewer() != null && review.reviewer().isBlank() && review.reviewedAt() == null
                    && review.referenceVerified() == null && review.criticalConflictFree() == null
                    && review.coreClaimsSupported() == null && review.refusalCorrect() == null
                    && review.facts().stream().allMatch(fact -> fact.covered() == null)) {
                continue; // untouched template rows remain pending, never imply human approval
            }
            reviews.put(review.caseId(), review);
        }
        RagAccuracyEvaluator evaluator = new RagAccuracyEvaluator(mapper);
        List<CaseResult> results = new ArrayList<>();
        for (var item : original.cases()) {
            String hash = evaluator.resultHash(original.runId(), original.dataset().sha256(), item.sample(), item.observation());
            if (!hash.equals(item.resultSha256())) {
                throw new IllegalArgumentException("Report observation or labels were modified: " + item.sample().id());
            }
            if (corpus != null && item.observation().errorCode() == null) {
                corpus.verifyEvidence(item.sample().knowledgeBaseId(), item.observation().evidence());
            }
            Review review = reviews.getOrDefault(item.sample().id(), item.evaluation().humanReview());
            results.add(new CaseResult(item.sample(), item.observation(), hash,
                    evaluator.evaluate(original.runId(), original.dataset().sha256(), item.sample(), item.observation(), review)));
        }
        return RagAccuracyReport.build(original.runId(), original.createdAt(), original.dataset(),
                original.configuration(), original.limitations(), results);
    }

    private static Path snapshotDataset(Path source, Path directory, String runId, RagAccuracyDataset dataset,
                                         ObjectMapper mapper) throws IOException {
        Files.createDirectories(directory);
        Path snapshot = directory.resolve("rag-" + runId + "-dataset.jsonl");
        Files.write(snapshot, Files.readAllBytes(source), java.nio.file.StandardOpenOption.CREATE_NEW);
        if (!RagAccuracyDataset.load(snapshot, mapper).equals(dataset)) {
            throw new IllegalArgumentException("Dataset changed while reading labels");
        }
        return snapshot;
    }

    private static Path resolveSnapshot(Path reportPath, String recordedPath) {
        Path local = reportPath.toAbsolutePath().getParent().resolve(Path.of(recordedPath.replace('\\', '/')).getFileName());
        return Files.exists(local) ? local : Path.of(recordedPath);
    }
}
