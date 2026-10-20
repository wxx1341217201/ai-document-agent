package com.wxx.aidocumentagent.agent.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.wxx.aidocumentagent.AiDocumentAgentApplication;
import com.wxx.aidocumentagent.agent.AgentCancellationToken;
import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkDataset.Task;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Observation;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Review;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Turn;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkReport.CaseResult;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkReport.DatasetInfo;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 独立进程、顺序任务、真实 AgentRouter。不会改写标签、生产模型或现有会话。 */
@Component
@ConditionalOnProperty(prefix = "app.benchmark.agent", name = "enabled", havingValue = "true")
public final class AgentBenchmarkRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentBenchmarkRunner.class);
    private final AgentRouter router;
    private final AgentBenchmarkSessions sessions;
    private final AgentBenchmarkCorpus corpus;
    private final Environment environment;
    private final RetrievalFirstProperties retrievalFirst;
    private final ReActProperties react;
    private final PlanProperties plan;
    private final ObjectMapper mapper;
    private final LongSupplier nanoTime;
    private Path diagnosticJournal;

    @Autowired
    public AgentBenchmarkRunner(AgentRouter router, AgentBenchmarkSessions sessions, AgentBenchmarkCorpus corpus,
                                 Environment environment, RetrievalFirstProperties retrievalFirst,
                                 ReActProperties react, PlanProperties plan, ObjectMapper mapper) {
        this(router, sessions, corpus, environment, retrievalFirst, react, plan, mapper, System::nanoTime);
    }

    AgentBenchmarkRunner(AgentRouter router, AgentBenchmarkSessions sessions, AgentBenchmarkCorpus corpus,
                         Environment environment, RetrievalFirstProperties retrievalFirst,
                         ReActProperties react, PlanProperties plan, ObjectMapper mapper, LongSupplier nanoTime) {
        this.router = router; this.sessions = sessions; this.corpus = corpus; this.environment = environment;
        this.retrievalFirst = retrievalFirst; this.react = react; this.plan = plan; this.mapper = mapper;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    @Override public void run(ApplicationArguments arguments) throws IOException {
        Path report = runBenchmark(Path.of(environment.getProperty("app.benchmark.agent.dataset", "benchmarks/agent/agent-tasks.jsonl")),
                Path.of(environment.getProperty("app.benchmark.agent.report-directory", "reports")));
        log.info("M19C Agent benchmark report: {}", report.toAbsolutePath());
        requireTarget(mapper.readValue(Files.readString(report, StandardCharsets.UTF_8), AgentBenchmarkReport.class),
                environment.getProperty("app.benchmark.agent.require-target", Boolean.class, false));
    }

    Path runBenchmark(Path datasetPath, Path directory) throws IOException {
        AgentBenchmarkDataset dataset = AgentBenchmarkDataset.load(datasetPath, mapper);
        dataset.requireBenchmarkCoverage();
        int maxSeconds = environment.getProperty("app.benchmark.agent.max-run-seconds", Integer.class, 3600);
        int maxTasksPerMode = environment.getProperty("app.benchmark.agent.max-tasks-per-mode", Integer.class, 0);
        if (maxSeconds < 1 || maxSeconds > 3600) throw new IllegalArgumentException("max-run-seconds must be 1..3600");
        if (maxTasksPerMode < 0 || maxTasksPerMode > 100_000) throw new IllegalArgumentException("max-tasks-per-mode must be 0..100000");
        String runId = UUID.randomUUID().toString(), startedAt = Instant.now().toString();
        Path snapshot = snapshotDataset(datasetPath, directory, runId, dataset, mapper);
        String datasetSha = AgentBenchmarkText.sha256(Files.readAllBytes(snapshot));
        int maxErrors = environment.getProperty("app.benchmark.agent.max-consecutive-errors", Integer.class, 3);
        if (maxErrors < 1 || maxErrors > 10) throw new IllegalArgumentException("max-consecutive-errors must be 1..10");
        Map<String, Object> config = configuration(maxErrors);
        config.put("targetTaskSuccessRate", AgentBenchmarkReport.TARGET_SUCCESS_RATE);
        config.put("datasetSnapshotPath", snapshot.toAbsolutePath().toString());
        config.put("maxRunSeconds", maxSeconds); config.put("maxTasksPerMode", maxTasksPerMode);
        var budget = new RunBudget(Duration.ofSeconds(maxSeconds).toNanos(), nanoTime.getAsLong(), nanoTime);
        List<String> limitations = new ArrayList<>(List.of(
                "Labels are source-grounded assistant-curated drafts until each task is human-reviewed.",
                "Only the current KB 2 literary corpus is covered; related variants are not independent business domains.",
                "Multi-turn history contains actual Agent answers. Conversation cache, HTTP/WebSocket and durable worker recovery are outside this benchmark.",
                "Controlled faults affect only the declared task/turn/tool occurrence. They do not replace the model or fabricate documents.",
                "Runtime support verification provides feedback only; independent human review determines semantic correctness."));
        AgentBenchmarkCorpus.Baseline baseline = null;
        String preflightError = null;
        try { baseline = corpus.snapshot(dataset); }
        catch (RuntimeException e) { preflightError = "CORPUS_PREFLIGHT_FAILED:" + safeError(e); limitations.add(preflightError); }
        if (baseline != null) {
            Path corpusSnapshot = directory.resolve("agent-" + runId + "-corpus.json");
            Files.writeString(corpusSnapshot, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(baseline),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            config.put("corpusSnapshotPath", corpusSnapshot.toAbsolutePath().toString());
            config.put("corpusSnapshotSha256", AgentBenchmarkText.sha256(Files.readAllBytes(corpusSnapshot)));
        }
        boolean preflightOnly = environment.getProperty("app.benchmark.agent.preflight-only", Boolean.class, false);
        config.put("preflightOnly", preflightOnly);
        var evaluator = new AgentBenchmarkEvaluator(mapper);
        List<CaseResult> results = new ArrayList<>();
        Map<AgentMode, Integer> errors = new EnumMap<>(AgentMode.class);
        Map<AgentMode, Integer> attempted = new EnumMap<>(AgentMode.class);
        Path journal = directory.resolve("agent-" + runId + "-observations.jsonl");
        Files.writeString(journal, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        config.put("observationJournalPath", journal.toAbsolutePath().toString());
        diagnosticJournal = directory.resolve("agent-" + runId + "-diagnostics.jsonl");
        Files.writeString(diagnosticJournal, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        config.put("diagnosticJournalPath", diagnosticJournal.toAbsolutePath().toString());
        for (Task task : dataset.tasks()) {
            Observation observation;
            if (preflightError != null) observation = Observation.notRun(preflightError);
            else if (preflightOnly) observation = Observation.notRun("CORPUS_PREFLIGHT_ONLY");
            else if (budget.expired()) observation = Observation.notRun("RUN_TIME_BUDGET_EXCEEDED");
            else if (maxTasksPerMode > 0 && attempted.getOrDefault(task.mode(), 0) >= maxTasksPerMode) observation = Observation.notRun("MODE_SAMPLE_LIMIT");
            else if (errors.getOrDefault(task.mode(), 0) >= maxErrors) observation = Observation.notRun("MODE_CONSECUTIVE_ERROR_LIMIT");
            else {
                observation = observe(task, runId, Objects.requireNonNull(baseline), budget);
                if (observation.executed()) attempted.merge(task.mode(), 1, Integer::sum);
                errors.put(task.mode(), observation.errorCode() == null ? 0 : errors.getOrDefault(task.mode(), 0) + 1);
            }
            var item = new CaseResult(task, observation, evaluator.resultHash(runId, datasetSha, task, observation),
                    evaluator.evaluate(runId, datasetSha, task, observation, null));
            results.add(item);
            Files.writeString(journal, mapper.writeValueAsString(item) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            log.info("M19C case={} mode={} turns={} status={}", task.caseId(), task.mode(), observation.turns().size(), item.evaluation().status());
        }
        boolean stable = false;
        if (baseline != null) {
            try { stable = corpus.stable(dataset.knowledgeBaseId(), baseline); }
            catch (RuntimeException e) { limitations.add("CORPUS_FINAL_CHECK_FAILED:" + safeError(e)); }
            if (!stable) limitations.add("CORPUS_CHANGED_OR_FINAL_CHECK_FAILED");
        }
        config.put("corpusStable", stable);
        boolean codeStable = Objects.equals(config.get("applicationClassesSha256"), applicationClasses());
        config.put("applicationCodeStable", codeStable);
        if (!codeStable) limitations.add("APPLICATION_CLASSES_CHANGED_DURING_RUN");
        config.put("corpusChunks", baseline == null ? 0 : baseline.sources().size());
        config.put("diagnosticJournalSha256", AgentBenchmarkText.sha256(Files.readAllBytes(diagnosticJournal)));
        var info = new DatasetInfo(datasetPath.toAbsolutePath().toString(), datasetSha, dataset.knowledgeBaseId(), dataset.tasks().size(),
                provenance(dataset), baseline == null ? null : baseline.fingerprint());
        return new AgentBenchmarkReportWriter(mapper).write(directory, AgentBenchmarkReport.build(runId, startedAt, info, config, limitations, results));
    }

    private Observation observe(Task task, String runId, AgentBenchmarkCorpus.Baseline baseline, RunBudget budget) {
        if (budget.expired()) return Observation.notRun("RUN_TIME_BUDGET_EXCEEDED");
        long started = System.nanoTime();
        Long conversationId = null;
        List<AgentMemoryMessage> history = new ArrayList<>();
        List<Turn> turns = new ArrayList<>();
        String error = null;
        try {
            if (task.turns().size() > 1) conversationId = sessions.open(task.knowledgeBaseId(), task.mode(), runId, task.caseId());
            for (int i = 0; i < task.turns().size(); i++) {
                long remainingNanos = budget.remainingNanos();
                if (remainingNanos == 0) { error = "RUN_TIME_BUDGET_EXCEEDED"; break; }
                String question = task.turns().get(i);
                var request = new AgentRequest(question, task.knowledgeBaseId(), conversationId, task.mode(), Map.of());
                var trace = new AgentBenchmarkTrace(task.fault(), i + 1);
                var context = AgentExecutionContext.forObservedRequest(request, UUID.randomUUID().toString(),
                        Instant.now().plusNanos(Math.min(deadline(task.mode()).toNanos(), remainingNanos)),
                        new AgentConversationContext(task.knowledgeBaseId(), conversationId, history),
                        event -> { }, new AgentCancellationToken(), trace);
                long turnStarted = System.nanoTime();
                AgentResult result = null;
                AgentBenchmarkTrace.Snapshot captured;
                List<RagEvidenceChunk> evidence = List.of();
                String turnError = null;
                boolean evidenceVerified = false;
                try {
                    result = router.execute(request, context);
                    captured = trace.freeze();
                    evidence = corpus.verify(task.knowledgeBaseId(), baseline, captured.evidence());
                    evidenceVerified = true;
                    if (result.failure() != null) turnError = result.failure().code().name();
                    // Sanitize before anything is written to the journal, reports or the next turn.
                    if (result.answer() != null) AgentBenchmarkText.visible(result.answer());
                }
                catch (RuntimeException e) {
                    captured = trace.freeze();
                    result = null;
                    evidence = List.of();
                    turnError = safeError(e);
                }
                var observed = new Turn(i + 1, question, AgentBenchmarkText.canonicalHash(mapper, history), history.size(), conversationId,
                        result, evidence, result == null ? captured.steps() : result.steps(), result == null ? captured.tools() : result.toolCalls(),
                        captured.dispatches(), evidenceVerified, false, elapsed(turnStarted), turnError);
                if (diagnosticJournal != null) {
                    try {
                        Files.writeString(diagnosticJournal, mapper.writeValueAsString(Map.of("caseId", task.caseId(),
                                "turn", i + 1, "diagnostics", captured.diagnostics())) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    }
                    catch (IOException failure) { throw new IllegalStateException("DIAGNOSTIC_WRITE_FAILED", failure); }
                }
                turns.add(observed);
                if (result == null || result.status() != AgentResult.Status.COMPLETED) {
                    // Input/model/storage outages are distinct from an Agent's bounded task failure.
                    if (result == null || List.of("MODEL_TIMEOUT", "MODEL_UNAVAILABLE", "STORAGE_FAILED").contains(turnError)) error = turnError;
                    break;
                }
                history.add(new AgentMemoryMessage(history.size() + 1L, AgentMemoryMessage.Role.USER, question));
                history.add(new AgentMemoryMessage(history.size() + 1L, AgentMemoryMessage.Role.ASSISTANT, observed.result().answer()));
            }
        }
        catch (RuntimeException e) { error = safeError(e); }
        finally {
            if (conversationId != null) {
                try { sessions.close(task.knowledgeBaseId(), conversationId); }
                catch (RuntimeException e) { error = "SESSION_CLEANUP_FAILED:" + safeError(e); }
            }
        }
        return new Observation(true, turns, elapsed(started), error);
    }

    private Duration deadline(AgentMode mode) {
        return switch (mode) { case RETRIEVAL_FIRST -> retrievalFirst.getDeadline(); case REACT -> react.getDeadline(); case PLAN_EXECUTE_REFLECT -> plan.getDeadline(); };
    }
    private Map<String, Object> configuration(int maxErrors) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "LIVE"); config.put("pipeline", "AgentRouter.execute"); config.put("concurrency", 1);
        config.put("memory", "actual_completed_turn_snapshots"); config.put("maxConsecutiveErrorsPerMode", maxErrors);
        config.put("deployment", environment.getProperty("app.benchmark.agent.deployment", "unspecified"));
        for (String name : List.of("spring.ai.openai.chat.options.model", "spring.ai.openai.chat.model", "app.rag.model",
                "spring.ai.openai.chat.temperature", "spring.ai.openai.max-retries", "spring.ai.openai.timeout", "app.rag.connect-timeout", "app.rag.read-timeout",
                "app.rag.chat-timeout", "app.vector.embedding.model", "app.vector.qdrant.collection-name", "app.keyword.alias",
                "app.chunking.chunk-size", "app.chunking.overlap", "app.retrieval.vector-top-k", "app.retrieval.keyword-top-k",
                "app.retrieval.rrf-k", "app.retrieval.final-top-k", "app.retrieval.vector-timeout", "app.retrieval.keyword-timeout",
                "app.retrieval.total-timeout", "app.vector.embedding.timeout", "app.vector.embedding.max-retries", "app.vector.embedding.dimensions",
                "app.vector.qdrant.connect-timeout", "app.vector.qdrant.request-timeout", "app.vector.qdrant.initialization-strategy",
                "app.keyword.connect-timeout", "app.keyword.socket-timeout",
                "app.rerank.enabled", "app.rerank.provider", "app.rerank.model", "app.rerank.connect-timeout", "app.rerank.read-timeout",
                "app.rerank.max-retries", "app.rerank.max-candidates", "app.rerank.max-text-characters", "app.rag.context-window-tokens",
                "app.rag.reserved-output-tokens", "app.rag.max-context-characters", "app.rag.max-chunk-characters",
                "app.keyword.initialize-schema", "app.rag.query-expansion-enabled", "app.rag.query-expansion-timeout",
                "app.rag.max-retrieval-queries", "app.rag.retrieval-candidate-top-k", "app.rag.disable-thinking", "app.agent.react.model-timeout",
                "app.agent.plan.model-timeout")) config.put(name, environment.getProperty(name, "unspecified"));
        config.put("retrievalFirst", mapper.convertValue(retrievalFirst, Map.class));
        config.put("react", mapper.convertValue(react, Map.class)); config.put("plan", mapper.convertValue(plan, Map.class));
        Map<String, String> code = new LinkedHashMap<>();
        for (String path : List.of("agent/AgentRouter", "agent/react/ReActAgent", "agent/react/ToolRegistry", "agent/react/ToolExecutionContext",
                "agent/react/SearchKnowledgeBaseTool", "agent/react/GetChunkContextTool", "agent/react/GetDocumentMetadataTool",
                "agent/plan/PlanExecuteReflectAgent", "agent/plan/Planner", "agent/plan/Executor", "agent/plan/Reflector", "agent/plan/PlanValidator",
                "rag/application/RetrievalFirstAgent", "rag/application/RagPromptTemplates", "rag/application/ModelRetrievalQuestionRewriter",
                "rag/application/RagContextBuilder", "rag/application/CitationValidator", "rag/infrastructure/chat/SpringAiRagChatClient",
                "agent/react/SpringAiReActModelClient", "agent/plan/SpringAiPlanModelClient", "agent/benchmark/AgentBenchmarkEvaluator",
                "rag/benchmark/RagAccuracyEvaluator", "retrieval/rerank/HttpCrossEncoderReranker",
                "agent/AgentConversationSupport", "agent/EvidenceAnswerSupport", "rag/infrastructure/chat/RagChatOptions",
                "rag/application/RagQueryRetrievalService", "rag/application/RagRetrievalQueryPlanner",
                "agent/react/EvidenceTokenBudget", "rag/application/EvidenceOverlap",
                "agent/ModelFailureDiagnostics", "agent/AgentToolRequirements", "agent/plan/EvidenceCoverageVerifier",
                "agent/ClaimEvidenceBinding", "agent/ClaimEvidenceValidator", "agent/ClaimSupportVerifier", "rag/application/EvidenceRelevance")) {
            try (var stream = AgentBenchmarkRunner.class.getResourceAsStream("/com/wxx/aidocumentagent/" + path + ".class")) {
                code.put(path, stream == null ? "unavailable" : AgentBenchmarkText.sha256(stream.readAllBytes()));
            }
        }
        config.put("codeSha256", code);
        var classes = applicationClasses();
        config.put("applicationClassesSha256", classes);
        config.put("applicationClassesClosureSha256", AgentBenchmarkText.canonicalHash(mapper, classes));
        config.put("judge", "human_final_no_llm_judge");
        return config;
    }

    private Map<String, String> applicationClasses() throws IOException {
        Map<String, String> result = new java.util.TreeMap<>();
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:com/wxx/aidocumentagent/**/*.class")) {
            String url = resource.getURL().toExternalForm();
            String key = url.substring(url.lastIndexOf("com/wxx/aidocumentagent/"));
            try (var input = resource.getInputStream()) { result.put(key, AgentBenchmarkText.sha256(input.readAllBytes())); }
        }
        if (result.isEmpty()) throw new IOException("APPLICATION_CLASS_SNAPSHOT_EMPTY");
        return Map.copyOf(result);
    }

    private static String safeError(RuntimeException e) {
        return e instanceof BusinessException b ? b.getErrorCode().code() : e instanceof IllegalArgumentException
                && e.getMessage() != null && e.getMessage().matches("[A-Z_]+") ? e.getMessage() : e.getClass().getSimpleName();
    }
    private double elapsed(long started) { return Math.max(0, System.nanoTime() - started) / 1_000_000.0; }
    private record RunBudget(long maximumNanos, long startedNanos, LongSupplier nanoTime) {
        long remainingNanos() { return Math.max(0L, maximumNanos - (nanoTime.getAsLong() - startedNanos)); }
        boolean expired() { return remainingNanos() == 0; }
    }
    private static String provenance(AgentBenchmarkDataset dataset) {
        return dataset.tasks().stream().map(Task::labelProvenance).distinct().sorted().collect(java.util.stream.Collectors.joining(","));
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        for (String argument : args) { String[] parts = argument.split("=", 2); options.put(parts[0], parts.length == 1 ? "true" : parts[1]); }
        if (options.containsKey("--live")) {
            SpringApplication app = new SpringApplication(AiDocumentAgentApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            List<String> boot = liveArguments(args);
            try (var context = app.run(boot.toArray(String[]::new))) { log.info("M19C benchmark context closing"); }
            return;
        }
        ObjectMapper mapper = new JsonMapper();
        Path directory = Path.of(options.getOrDefault("--report-directory", "reports"));
        AgentBenchmarkReport report;
        if (options.containsKey("--audit-report")) {
            Path source = Path.of(options.get("--audit-report"));
            AgentBenchmarkAudit.write(source, directory, mapper);
            requireTarget(mapper.readValue(Files.readString(source, StandardCharsets.UTF_8), AgentBenchmarkReport.class),
                    options.containsKey("--require-target"));
            return;
        }
        else if (options.containsKey("--review-report")) {
            report = applyReviews(Path.of(options.get("--review-report")), Path.of(Objects.requireNonNull(options.get("--reviews"), "--reviews required")), mapper);
        }
        else if (options.containsKey("--validate-only")) {
            Path path = Path.of(options.getOrDefault("--dataset", "benchmarks/agent/agent-tasks.jsonl"));
            var dataset = AgentBenchmarkDataset.load(path, mapper); dataset.requireBenchmarkCoverage();
            String runId = UUID.randomUUID().toString();
            Path snapshot = snapshotDataset(path, directory, runId, dataset, mapper);
            String hash = AgentBenchmarkText.sha256(Files.readAllBytes(snapshot));
            var evaluator = new AgentBenchmarkEvaluator(mapper);
            var cases = dataset.tasks().stream().map(t -> {
                var obs = Observation.notRun("DATASET_VALIDATION_ONLY");
                return new CaseResult(t, obs, evaluator.resultHash(runId, hash, t, obs), evaluator.evaluate(runId, hash, t, obs, null));
            }).toList();
            report = AgentBenchmarkReport.build(runId, Instant.now().toString(), new DatasetInfo(path.toAbsolutePath().toString(), hash,
                    dataset.knowledgeBaseId(), dataset.tasks().size(), provenance(dataset), null),
                    Map.of("mode", "VALIDATE_ONLY", "datasetSnapshotPath", snapshot.toAbsolutePath().toString()),
                    List.of("Only dataset structure and coverage were checked; no database, Agent, retrieval or model call was made.",
                            "All labels remain drafts until human-reviewed."), cases);
        }
        else throw new IllegalArgumentException("Use --live, --validate-only, --audit-report=<json>, or --review-report=<json> --reviews=<jsonl>");
        Path written = new AgentBenchmarkReportWriter(mapper).write(directory, report);
        System.out.println("M19C report: " + written.toAbsolutePath());
        System.out.println("TaskSuccessRate=" + report.overall().taskSuccessRate() + "; target=" + report.targetStatus());
        requireTarget(report, options.containsKey("--require-target"));
    }

    static List<String> liveArguments(String[] args) {
        List<String> boot = new ArrayList<>(List.of(args)); boot.remove("--live");
        Map<String, String> forced = new LinkedHashMap<>(Map.of("app.benchmark.agent.enabled", "true", "app.benchmark.rag.enabled", "false",
                "app.benchmark.retrieval.enabled", "false", "spring.flyway.enabled", "false", "app.ingestion.enabled", "false",
                "app.progress.enabled", "false", "app.agent.execution.enabled", "false", "spring.rabbitmq.listener.simple.auto-startup", "false",
                "app.keyword.initialize-schema", "false"));
        forced.put("app.vector.qdrant.initialization-strategy", "VALIDATE_EXISTING");
        boot.removeIf(a -> forced.keySet().stream().anyMatch(k -> a.equals("--" + k) || a.startsWith("--" + k + "=")));
        forced.forEach((k, v) -> boot.add("--" + k + "=" + v));
        return List.copyOf(boot);
    }

    static void requireTarget(AgentBenchmarkReport report, boolean required) {
        if (required && !"MET".equals(report.targetStatus())) {
            throw new IllegalStateException("M19C_TARGET_UNMET_OR_UNVERIFIED:" + report.targetStatus());
        }
    }

    public static AgentBenchmarkReport applyReviews(Path reportPath, Path reviewPath, ObjectMapper mapper) throws IOException {
        return replay(reportPath, reviewPath, mapper);
    }

    static AgentBenchmarkReport replay(Path reportPath, Path reviewPath, ObjectMapper mapper) throws IOException {
        var original = mapper.readValue(Files.readString(reportPath, StandardCharsets.UTF_8), AgentBenchmarkReport.class);
        boolean supported = "m19c-report-v1".equals(original.schemaVersion()) && "m19c-human-final-v1".equals(original.policyVersion())
                || "m19c-report-v2".equals(original.schemaVersion()) && AgentBenchmarkEvaluator.POLICY_VERSION.equals(original.policyVersion());
        if (!supported
                || !Objects.equals(original.runIntegritySha256(), AgentBenchmarkReport.integrity(original.runId(), original.createdAt(),
                    original.dataset(), original.configuration(), original.limitations(), original.cases()))) throw new IllegalArgumentException("Unsupported or modified run inputs");
        Path snapshot = resolveSnapshot(reportPath, String.valueOf(original.configuration().get("datasetSnapshotPath")));
        if (!AgentBenchmarkText.sha256(Files.readAllBytes(snapshot)).equals(original.dataset().sha256())) throw new IllegalArgumentException("Dataset snapshot changed");
        var dataset = AgentBenchmarkDataset.load(snapshot, mapper); dataset.requireBenchmarkCoverage();
        if (original.dataset().knowledgeBaseId() != dataset.knowledgeBaseId() || original.dataset().tasks() != dataset.tasks().size()
                || original.cases().size() != dataset.tasks().size()) throw new IllegalArgumentException("Report must preserve the fixed full denominator");
        Map<String, Task> labels = new HashMap<>(); dataset.tasks().forEach(t -> labels.put(t.caseId(), t));
        AgentBenchmarkCorpus.Baseline frozen = null;
        if (original.configuration().containsKey("corpusSnapshotPath")) {
            Path corpusPath = resolveSnapshot(reportPath, String.valueOf(original.configuration().get("corpusSnapshotPath")));
            if (!AgentBenchmarkText.sha256(Files.readAllBytes(corpusPath)).equals(original.configuration().get("corpusSnapshotSha256"))) {
                throw new IllegalArgumentException("Corpus snapshot changed");
            }
            frozen = mapper.readValue(Files.readString(corpusPath, StandardCharsets.UTF_8), AgentBenchmarkCorpus.Baseline.class);
            if (!Objects.equals(frozen.fingerprint(), original.dataset().referencedChunkFingerprint())
                    || frozen.sources().size() != ((Number) original.configuration().get("corpusChunks")).intValue()) {
                throw new IllegalArgumentException("Corpus fingerprint or size changed");
            }
            AgentBenchmarkCorpus.validateLabels(dataset, frozen);
        }
        else if ("m19c-report-v2".equals(original.schemaVersion()) && original.dataset().referencedChunkFingerprint() != null) {
            throw new IllegalArgumentException("Frozen corpus required");
        }
        var unique = new HashSet<String>();
        for (var item : original.cases()) if (!unique.add(item.task().caseId()) || !item.task().equals(labels.get(item.task().caseId()))) throw new IllegalArgumentException("Task labels changed");
        Map<String, Review> reviews = new HashMap<>();
        for (String line : reviewPath == null ? List.<String>of() : Files.readAllLines(reviewPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            var review = mapper.readValue(line, Review.class);
            if (review.untouched()) continue;
            if (!unique.contains(review.caseId()) || reviews.putIfAbsent(review.caseId(), review) != null) throw new IllegalArgumentException("Unknown or duplicate review");
        }
        var evaluator = new AgentBenchmarkEvaluator(mapper);
        List<CaseResult> results = new ArrayList<>();
        for (var item : original.cases()) {
            String hash = evaluator.resultHash(original.runId(), original.dataset().sha256(), item.task(), item.observation());
            if (!hash.equals(item.resultSha256())) throw new IllegalArgumentException("Modified task observation");
            if (frozen != null) for (var turn : item.observation().turns()) {
                AgentBenchmarkCorpus.verifyFrozen(dataset.knowledgeBaseId(), frozen, turn.evidence());
            }
            Review review = reviews.getOrDefault(item.task().caseId(), item.evaluation().humanReview());
            results.add(new CaseResult(item.task(), item.observation(), hash,
                    evaluator.evaluate(original.runId(), original.dataset().sha256(), item.task(), item.observation(), review)));
        }
        return AgentBenchmarkReport.build(original.runId(), original.createdAt(), original.dataset(), original.configuration(), original.limitations(), results);
    }

    private static Path resolveSnapshot(Path reportPath, String recorded) {
        Path original = Path.of(recorded);
        if (Files.isRegularFile(original)) return original;
        // Keep a report and its snapshots together when moving an evaluation bundle.
        Path sibling = reportPath.toAbsolutePath().getParent().resolve(original.getFileName());
        return Files.isRegularFile(sibling) ? sibling : original;
    }

    private static Path snapshotDataset(Path source, Path directory, String runId, AgentBenchmarkDataset dataset, ObjectMapper mapper) throws IOException {
        Files.createDirectories(directory);
        Path snapshot = directory.resolve("agent-" + runId + "-dataset.jsonl");
        Files.write(snapshot, Files.readAllBytes(source), StandardOpenOption.CREATE_NEW);
        if (!AgentBenchmarkDataset.load(snapshot, mapper).equals(dataset)) throw new IllegalArgumentException("Dataset changed while reading");
        return snapshot;
    }
}
