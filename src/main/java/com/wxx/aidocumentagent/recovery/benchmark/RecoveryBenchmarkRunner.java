package com.wxx.aidocumentagent.recovery.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkEvaluator.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkObservation.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkReport.Case;

/** Standalone orchestrator; uses real production services in isolated child JVMs, never the user's live application. */
public final class RecoveryBenchmarkRunner {
    private final ObjectMapper mapper;
    private final RecoveryFaultEnvironment environment;
    private final RecoveryBenchmarkClient client;
    private final Duration recoveryTimeout;
    private final String runId;
    private final RecoveryBenchmarkEvaluator evaluator = new RecoveryBenchmarkEvaluator();

    private RecoveryBenchmarkRunner(ObjectMapper mapper, RecoveryFaultEnvironment environment, Duration recoveryTimeout, String runId) {
        this.mapper = mapper; this.environment = environment; this.client = new RecoveryBenchmarkClient(mapper, environment);
        this.recoveryTimeout = recoveryTimeout; this.runId = runId;
    }

    private Case execute(RecoveryBenchmarkPlan.Sample sample) {
        Snapshot before = null, after = null;
        Execution checkpoint = null, recovered = null;
        boolean attempted = false, faultVerified = false, sameExecution = true;
        boolean checkingConversationAuthority = false;
        long faultStarted = 0;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("environmentRunId", runId);
        evidence.put("composeProject", environment.description().get("composeProject"));
        List<String> checkpointLoss = new ArrayList<>();
        List<String> checkpointFailures = new ArrayList<>();
        String expectedAnswer = null;
        try {
            long kb = environment.knowledgeBaseId();
            JsonNode conversation = client.post("/api/v1/knowledge-bases/" + kb + "/conversations",
                    Map.of("title", "m19d:" + runId + ":" + sample.caseId(), "agentMode", "REACT"), null);
            long conversationId = conversation.path("id").asLong();
            for (int turn = 1; turn <= sample.historyTurns(); turn++) client.post("/api/v1/conversations/" + conversationId + "/messages",
                    Map.of("content", sample.caseId() + " persisted user turn " + turn), null);
            before = client.snapshot(conversationId, kb);
            if (!before.cachePresent() || before.messages().size() != sample.historyTurns() * 2
                    || before.lastMessageSequence() != sample.historyTurns() * 2L) throw new IOException("DURABLE_BASELINE_NOT_VERIFIED");
            client.verifyRestHistory(before);
            if (!client.rejectsCrossKnowledgeBase(conversationId)) throw new IOException("CROSS_KNOWLEDGE_BASE_GUARD_FAILED");
            evidence.put("crossKnowledgeBaseDenied", true);
            evidence.put("persistedMessages", before.messages().size());
            evidence.put("memoryMessages", before.memory().size());
            String scope = "?knowledgeBaseId=" + kb;
            String cache = "/benchmark/recovery/cache/" + conversationId;
            Map<String, Object> executionRequest = null;
            String executionPath = null, executionKey = null, id = null;
            if (List.of(RecoveryBenchmarkPlan.Fault.APPLICATION_RESTART, RecoveryBenchmarkPlan.Fault.AGENT_STEP_INTERRUPTION,
                    RecoveryBenchmarkPlan.Fault.WORKER_REDELIVERY, RecoveryBenchmarkPlan.Fault.WEBSOCKET_RECONNECT).contains(sample.fault())) {
                String question = sample.caseId() + (sample.fault() == RecoveryBenchmarkPlan.Fault.APPLICATION_RESTART ? "|RESULT" : "|STEP");
                expectedAnswer = sha(RecoveryFixtureAgent.answer(question, before.memory(), mapper));
                executionRequest = Map.of("knowledgeBaseId", kb, "conversationId", conversationId, "agentMode", "REACT", "question", question);
                executionKey = "m19d:" + runId + ":" + sample.caseId() + ":" + conversationId;
                JsonNode created = client.post("/api/v1/agent/executions", executionRequest, executionKey);
                id = created.path("executionId").asText();
                executionPath = "/api/v1/agent/executions/" + id;
                boolean resultBoundary = sample.fault() == RecoveryBenchmarkPlan.Fault.APPLICATION_RESTART;
                JsonNode boundary = client.poll(executionPath + scope, n -> {
                    Execution execution = Execution.from(n, mapper);
                    return List.of("FAILED", "CANCELLED", "SUCCEEDED").contains(execution.status())
                            || "RUNNING".equals(execution.status()) && execution.steps().containsKey("fixture_read")
                            && (resultBoundary ? execution.resultSha256() != null : execution.resultSha256() == null)
                            && execution.toolInvocations() >= 1;
                }, Duration.ofSeconds(15));
                checkpoint = Execution.from(boundary, mapper);
                if (!"RUNNING".equals(checkpoint.status())) {
                    evidence.put("preparationExecution", checkpoint);
                    throw new IOException("EXECUTION_TERMINATED_BEFORE_FAULT");
                }
                client.poll("/benchmark/recovery/execution/" + id + "/attempts" + scope, n -> n.path("waiting").asBoolean(), Duration.ofSeconds(3));
                evidence.put("agentAttemptsBefore", attempts(id, kb));
            }
            faultStarted = System.nanoTime(); attempted = true;
            switch (sample.fault()) {
                case REDIS_KEY_LOSS, REDIS_TTL_EXPIRY -> {
                    String operation = sample.fault() == RecoveryBenchmarkPlan.Fault.REDIS_KEY_LOSS ? "DELETE" : "EXPIRE";
                    JsonNode action = client.post(cache + "/" + operation + scope, null, null);
                    evidence.put("cacheFault", mapper.convertValue(action, Map.class));
                    client.poll(cache + scope, n -> !n.path("present").asBoolean(), Duration.ofSeconds(3));
                    faultVerified = action.path("wasPresent").asBoolean();
                }
                case REDIS_RESTART -> {
                    evidence.putAll(environment.restartRedis());
                    client.poll(cache + scope, n -> !n.path("present").asBoolean(), Duration.ofSeconds(5));
                    faultVerified = true;
                }
                case APPLICATION_RESTART, WORKER_REDELIVERY -> {
                    evidence.putAll(environment.killNode());
                    faultVerified = true;
                    environment.release(id);
                    environment.startNode();
                    evidence.put("newPid", environment.nodePid());
                    if (evidence.get("terminatedPid").equals(environment.nodePid())) throw new IOException("PROCESS_RESTART_NOT_VERIFIED");
                    JsonNode waiting = client.poll(executionPath + scope, n -> "PAUSED".equals(n.path("status").asText()), recoveryTimeout);
                    Execution restoredCheckpoint = Execution.from(waiting, mapper);
                    evidence.put("checkpointAfterRestartBeforeResume", restoredCheckpoint);
                    verifyCheckpoint(checkpoint, restoredCheckpoint, checkpointLoss, checkpointFailures);
                    // The repository's M17 queue is a durable MySQL row plus local worker, not an Agent Rabbit consumer.
                    sameExecution = sameRequest(executionRequest, executionKey, id);
                    client.post(executionPath + "/resume" + scope, null, null);
                }
                case AGENT_STEP_INTERRUPTION -> {
                    evidence.put("interrupt", mapper.convertValue(client.post("/benchmark/recovery/execution/" + id + "/interrupt" + scope, null, null), Map.class));
                    faultVerified = true;
                    JsonNode failed = client.poll(executionPath + scope, n -> "FAILED".equals(n.path("status").asText()), Duration.ofSeconds(15));
                    if (!failed.path("retryable").asBoolean() || !"TOOL_FAILED".equals(failed.path("errorCode").asText())) throw new IOException("CONTROLLED_INTERRUPT_NOT_RETRYABLE");
                    Execution restoredCheckpoint = Execution.from(failed, mapper);
                    evidence.put("checkpointAfterInterruptBeforeResume", restoredCheckpoint);
                    verifyCheckpoint(checkpoint, restoredCheckpoint, checkpointLoss, checkpointFailures);
                    environment.release(id);
                    client.post(executionPath + "/resume" + scope, null, null);
                }
                case WEBSOCKET_RECONNECT -> {
                    long sequence;
                    try (var subscription = client.subscribe(id, kb)) {
                        sequence = subscription.latestSequence();
                        evidence.put("initialSocketConnectionAttempts", subscription.connectionAttempts());
                    }
                    evidence.put("socketAborted", true); evidence.put("sequenceBefore", sequence);
                    environment.release(id);
                    client.poll(executionPath + scope, n -> "SUCCEEDED".equals(n.path("status").asText()), Duration.ofSeconds(15));
                    try (var reconnected = client.subscribe(id, kb)) {
                        evidence.put("reconnectedSocketConnectionAttempts", reconnected.connectionAttempts());
                        evidence.put("sequenceAfter", reconnected.latestSequence());
                        evidence.put("restStatusUrl", reconnected.statusUrl());
                        // Completion is committed before its progress event is emitted. The ack can precede that event.
                        // An event gap is evidence, while REST/history/checkpoints remain the recovery authority.
                        evidence.put("eventGapObserved", reconnected.latestSequence() > sequence);
                        if (!"SUCCEEDED".equals(client.get(reconnected.statusUrl()).path("status").asText())) throw new IOException("WS_REST_RECOVERY_NOT_VERIFIED");
                    }
                    faultVerified = true;
                }
            }
            if (executionPath != null) {
                recovered = Execution.from(client.poll(executionPath + scope, n -> List.of("SUCCEEDED", "FAILED", "CANCELLED")
                        .contains(n.path("status").asText()), recoveryTimeout), mapper);
                sameExecution &= sameRequest(executionRequest, executionKey, id);
                int attempts = attempts(id, kb); evidence.put("agentAttemptsAfter", attempts);
                evidence.put("readOnlyStepReplayed", attempts > (int) evidence.get("agentAttemptsBefore"));
                if (sample.fault() == RecoveryBenchmarkPlan.Fault.APPLICATION_RESTART
                        && attempts != (int) evidence.get("agentAttemptsBefore")) throw new IOException("COMMITTED_RESULT_REEXECUTED_AGENT");
            }
            checkingConversationAuthority = true;
            after = client.snapshot(conversationId, kb);
            checkingConversationAuthority = false;
            Evaluation evaluation = evaluator.evaluate(before, after, faultVerified, checkpoint, recovered, expectedAnswer, sameExecution);
            try { client.verifyRestHistory(after); }
            catch (IOException e) {
                if (evaluation.outcome() != Outcome.SESSION_LOST) evaluation = new Evaluation(Outcome.RECOVERY_FAILED, List.of("REST_AUTHORITY_CHECK_FAILED"));
            }
            if (!checkpointLoss.isEmpty()) evaluation = new Evaluation(Outcome.SESSION_LOST, checkpointLoss);
            else if (!checkpointFailures.isEmpty() && evaluation.outcome() != Outcome.SESSION_LOST) evaluation = new Evaluation(Outcome.RECOVERY_FAILED, checkpointFailures);
            return new Case(sample, true, faultVerified, elapsed(faultStarted), before, after, checkpoint, recovered, evidence, evaluation);
        }
        catch (Exception e) {
            if (checkingConversationAuthority && faultVerified && e instanceof RecoveryBenchmarkClient.HttpFailure http
                    && http.status == 404 && "NOT_FOUND".equals(http.apiCode)) checkpointLoss.add("PERSISTED_CONVERSATION_MISSING_OR_SCOPE_CHANGED");
            if (attempted && faultVerified && "FIXTURE_KNOWLEDGE_BASE_CHANGED".equals(e.getMessage())) checkpointLoss.add("PERSISTED_KNOWLEDGE_BASE_ID_CHANGED");
            Evaluation evaluation = new Evaluation(attempted ? Outcome.RECOVERY_FAILED : Outcome.NOT_RUN, List.of(safeError(e)));
            if (!checkpointLoss.isEmpty()) evaluation = new Evaluation(Outcome.SESSION_LOST, checkpointLoss);
            return new Case(sample, attempted, faultVerified, attempted ? elapsed(faultStarted) : null,
                    before, after, checkpoint, recovered, evidence, evaluation);
        }
    }

    private int attempts(String id, long kb) throws Exception {
        return client.get("/benchmark/recovery/execution/" + id + "/attempts?knowledgeBaseId=" + kb).path("agentAttempts").asInt();
    }
    private boolean sameRequest(Map<String, Object> request, String key, String id) throws Exception {
        for (int delivery = 0; delivery < 2; delivery++) {
            if (!id.equals(client.post("/api/v1/agent/executions", request, key).path("executionId").asText())) return false;
        }
        return true;
    }
    private static void verifyCheckpoint(Execution before, Execution restored, List<String> loss, List<String> failures) {
        if (before.resultSha256() != null && !before.resultSha256().equals(restored.resultSha256())) loss.add("COMMITTED_RESULT_CHECKPOINT_MISSING_BEFORE_RESUME");
        if (!java.util.Objects.equals(before.steps().get("fixture_read"), restored.steps().get("fixture_read"))) failures.add("COMMITTED_STEP_CHECKPOINT_MISSING_BEFORE_RESUME");
    }
    private static double elapsed(long started) { return Math.max(0, System.nanoTime() - started) / 1_000_000.0; }
    static String safeError(Throwable error) {
        while ((error instanceof java.util.concurrent.ExecutionException || error instanceof java.util.concurrent.CompletionException)
                && error.getCause() != null) error = error.getCause();
        String message = error.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{0,95}") ? message : error.getClass().getSimpleName();
    }
    private static Case notRun(RecoveryBenchmarkPlan.Sample sample, String reason) {
        return new Case(sample, false, false, null, null, null, null, null, Map.of(), new Evaluation(Outcome.NOT_RUN, List.of(reason)));
    }

    /** A fresh isolated conversation is prepared on each retry; no write request or injected fault is retried. */
    static Case executeWithPreparationRetries(RecoveryBenchmarkPlan.Sample sample,
                                              java.util.function.Function<RecoveryBenchmarkPlan.Sample, Case> execute) throws InterruptedException {
        List<Case> preparationFailures = new ArrayList<>();
        for (int preparation = 1; ; preparation++) {
            Case result = execute.apply(sample);
            boolean transientPreparation = !result.attempted() && result.evaluation().outcome() == Outcome.NOT_RUN
                    && result.evaluation().reasons().stream().anyMatch(reason -> List.of("HttpTimeoutException", "ConnectException",
                    "HTTP_502", "HTTP_503", "HTTP_504").contains(reason));
            if (!transientPreparation || preparation >= 3) {
                if (preparationFailures.isEmpty()) return result;
                Map<String, Object> evidence = new LinkedHashMap<>(result.faultEvidence());
                evidence.put("preparationAttempts", preparation);
                evidence.put("preparationFailures", List.copyOf(preparationFailures));
                return new Case(result.sample(), result.attempted(), result.faultVerified(), result.recoveryMillis(), result.before(),
                        result.after(), result.checkpoint(), result.recoveredExecution(), evidence, result.evaluation());
            }
            preparationFailures.add(result);
            Thread.sleep(150);
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String argument : args) {
            String[] parts = argument.split("=", 2); options.put(parts[0], parts.length == 1 ? "true" : parts[1]);
        }
        if (options.containsKey("--live") == options.containsKey("--validate-only")) throw new IllegalArgumentException("USE_LIVE_OR_VALIDATE_ONLY");
        ObjectMapper mapper = new JsonMapper();
        if (options.containsKey("--complete-report")) {
            if (!options.containsKey("--live")) throw new IllegalArgumentException("COMPLETION_REQUIRES_LIVE");
            completeReport(options, mapper);
            return;
        }
        int count = Integer.parseInt(options.getOrDefault("--samples", "1000"));
        long seed = Long.parseLong(options.getOrDefault("--seed", "20261002"));
        var plan = RecoveryBenchmarkPlan.generate(count, seed);
        String runId = UUID.randomUUID().toString();
        Path directory = Path.of(options.getOrDefault("--report-directory", "reports")).toAbsolutePath(); Files.createDirectories(directory);
        Path planPath = directory.resolve("recovery-" + runId + "-plan.json");
        Files.writeString(planPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path journal = directory.resolve("recovery-" + runId + "-observations.jsonl");
        Files.createFile(journal);
        List<Case> cases = new ArrayList<>();
        List<String> limitations = new ArrayList<>(List.of(
                "Controlled deterministic Agent; real MySQL, Redis, production memory/persistence/REST/M17 services and child JVM/process faults. This is not an LLM accuracy or production deployment claim.",
                "M17 resumes intermediate steps by replaying read-only Agent work. A committed final result bypasses the Agent; both paths are checked separately.",
                "M17 Agent worker uses local ThreadPoolTaskExecutor and durable MySQL rows. Killing the owned worker JVM also restarts its co-located API; no Rabbit Agent consumption is claimed.",
                "Existing durable execution results live in agent_execution.result_json; this endpoint does not append conversation messages. Previously completed conversation turns are verified independently.",
                "Connectivity failures, unverified injections and timeouts are RECOVERY_FAILED, not assumed SESSION_LOST. They block a conclusive loss-rate claim.",
                "Actual claim lease is max(configured lease, attempt deadline +30s), here 45s. Recovery latency includes real lease expiry, startup, injection and checks.",
                "Controlled Agent deadline is 15s; boundary cancellation checks run at most every 250ms. Child JVM heap is capped at 256MiB with two active processors. GET transient errors have at most three attempts; writes are never automatically retried.",
                "JDBC connect and Hikari acquisition timeouts are 10s; socket timeout is 15s. Flyway startup connection retries are bounded to three with 1s intervals. Fault and recovery latency includes actual connection waits."));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("profile", options.containsKey("--live") ? "CONTROLLED_AGENT_REAL_STORAGE_PROCESS_FAULTS" : "PLAN_VALIDATION_ONLY");
        config.put("mode", options.containsKey("--live") ? "LIVE" : "VALIDATE_ONLY");
        config.put("planSnapshot", planPath.toString()); config.put("observationJournal", journal.toString());
        config.put("concurrency", 1); config.put("javaVersion", System.getProperty("java.version")); config.put("realStorageVerified", false);
        config.put("codeSha256", codeFingerprints());
        config.put("configurationSha256", configurationFingerprints());
        boolean executionFailed = false;
        if (options.containsKey("--validate-only")) {
            for (var sample : plan.samples()) cases.add(notRun(sample, "FAULT_PLAN_VALIDATION_ONLY"));
            limitations.add("No storage connection, process restart, worker execution or WebSocket request was performed.");
        }
        else {
            executionFailed = runLive(options, runId, plan, mapper, config, limitations, cases, journal);
        }
        if (Files.size(journal) == 0) for (var result : cases) Files.writeString(journal, mapper.writeValueAsString(result) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        finish(runId, plan, config, limitations, cases, directory, mapper, executionFailed, options.containsKey("--live"));
    }

    private static Map<String, String> codeFingerprints() throws IOException {
        Map<String, String> code = new LinkedHashMap<>();
        for (Class<?> type : List.of(RecoveryBenchmarkRunner.class, RecoveryBenchmarkEvaluator.class, RecoveryBenchmarkPlan.class,
                RecoveryBenchmarkReport.class, RecoveryBenchmarkObservation.class, RecoveryBenchmarkClient.class,
                RecoveryFaultEnvironment.class, RecoveryBenchmarkNode.class, RecoveryFixtureService.class, RecoveryFixtureAgent.class,
                com.wxx.aidocumentagent.conversation.application.ConversationMemoryService.class,
                com.wxx.aidocumentagent.conversation.ConversationConfiguration.class,
                com.wxx.aidocumentagent.conversation.application.ConversationTurnPersistenceService.class,
                com.wxx.aidocumentagent.conversation.infrastructure.memory.RedisConversationMemoryStore.class,
                com.wxx.aidocumentagent.agent.execution.application.ExecutionStore.class,
                com.wxx.aidocumentagent.agent.execution.application.ExecutionRunner.class,
                com.wxx.aidocumentagent.agent.execution.application.ExecutionRecoveryScanner.class,
                com.wxx.aidocumentagent.progress.ProgressWebSocketHandler.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                code.put(type.getName(), stream == null ? "unavailable" : sha(stream.readAllBytes()));
            }
        }
        return code;
    }

    private static Map<String, String> configurationFingerprints() throws IOException {
        Map<String, String> configuration = new LinkedHashMap<>();
        try (var stream = RecoveryBenchmarkRunner.class.getResourceAsStream("/application-recovery-benchmark.yml")) {
            configuration.put("application-recovery-benchmark.yml", stream == null ? "unavailable" : sha(stream.readAllBytes()));
        }
        configuration.put("benchmarks/recovery/compose.yaml", sha(Files.readAllBytes(Path.of("benchmarks/recovery/compose.yaml"))));
        return configuration;
    }

    private static void finish(String runId, RecoveryBenchmarkPlan plan, Map<String, Object> config, List<String> limitations,
                               List<Case> cases, Path directory, ObjectMapper mapper, boolean executionFailed, boolean live) throws IOException {
        RecoveryBenchmarkReport report = RecoveryBenchmarkReport.build(runId, plan, config, limitations, cases, mapper);
        Path written = report.write(directory, mapper);
        System.out.println("M19D report: " + written);
        System.out.println("SessionLossRate=" + report.overall().sessionLossRate() + "; RecoverySuccessRate="
                + report.overall().recoverySuccessRate() + "; target=" + report.targetStatus());
        if (executionFailed || (live && !"MET".equals(report.targetStatus()))) System.exit(2);
    }

    static List<RecoveryBenchmarkPlan.Sample> completionSamples(RecoveryBenchmarkReport source, RecoveryBenchmarkPlan plan, ObjectMapper mapper) {
        if (!"m19d-report-v1".equals(source.schemaVersion()) || !"LIVE".equals(source.environment().get("mode"))
                || !"CONTROLLED_AGENT_REAL_STORAGE_PROCESS_FAULTS".equals(source.environment().get("profile"))
                || !Boolean.TRUE.equals(source.environment().get("realStorageVerified"))) throw new IllegalArgumentException("COMPLETION_SOURCE_NOT_VERIFIED_LIVE");
        if (source.seed() != plan.seed() || !source.planSha256().equals(hash(mapper, plan))) throw new IllegalArgumentException("COMPLETION_PLAN_HASH_MISMATCH");
        var verified = RecoveryBenchmarkReport.build(source.runId(), plan, source.environment(), source.limitations(), source.cases(), mapper);
        if (!verified.overall().equals(source.overall()) || !verified.byFault().equals(source.byFault())
                || !verified.targetStatus().equals(source.targetStatus())) throw new IllegalArgumentException("COMPLETION_SOURCE_SUMMARY_MISMATCH");
        for (Case result : source.cases()) {
            if (result.attempted() && (!result.faultVerified() || !List.of(Outcome.RECOVERED, Outcome.SESSION_LOST).contains(result.evaluation().outcome()))) {
                throw new IllegalArgumentException("COMPLETION_CANNOT_REPLACE_RECOVERY_ATTEMPTS");
            }
            if (!result.attempted() && result.evaluation().outcome() != Outcome.NOT_RUN) throw new IllegalArgumentException("INVALID_COMPLETION_SOURCE");
        }
        var pending = source.cases().stream().filter(c -> !c.attempted()).map(Case::sample).toList();
        if (pending.isEmpty()) throw new IllegalArgumentException("NO_UNATTEMPTED_SAMPLES");
        return pending;
    }

    static List<Case> mergeCompletion(List<Case> source, List<Case> completion) {
        Map<String, Case> replacements = new LinkedHashMap<>();
        for (Case result : completion) if (replacements.put(result.sample().caseId(), result) != null) throw new IllegalArgumentException("DUPLICATE_COMPLETION_SAMPLE");
        List<Case> merged = new ArrayList<>();
        for (Case original : source) {
            Case replacement = replacements.remove(original.sample().caseId());
            if (original.attempted()) {
                if (replacement != null) throw new IllegalArgumentException("COMPLETION_CANNOT_REPLACE_RECOVERY_ATTEMPTS");
                merged.add(original);
            }
            else {
                if (replacement == null || !replacement.sample().equals(original.sample())) throw new IllegalArgumentException("COMPLETION_PLAN_CHANGED");
                merged.add(replacement);
            }
        }
        if (!replacements.isEmpty()) throw new IllegalArgumentException("COMPLETION_PLAN_CHANGED");
        return List.copyOf(merged);
    }

    static void verifyCompletionFingerprints(Map<String, Object> source, Map<String, String> code, Map<String, String> configuration) {
        if (!(source.get("codeSha256") instanceof Map<?, ?> priorCode) || !priorCode.keySet().equals(code.keySet())) {
            throw new IllegalArgumentException("COMPLETION_CODE_FINGERPRINTS_MISSING");
        }
        // This entry point adds preparation retries and audited completion. All stateful services, checks and configuration must match.
        code.forEach((name, digest) -> {
            if (!name.equals(RecoveryBenchmarkRunner.class.getName()) && !digest.equals(priorCode.get(name))) {
                throw new IllegalArgumentException("COMPLETION_COMPONENT_CHANGED:" + name);
            }
        });
        if (!configuration.equals(source.get("configurationSha256"))) throw new IllegalArgumentException("COMPLETION_CONFIGURATION_CHANGED");
    }

    private static void completeReport(Map<String, String> options, ObjectMapper mapper) throws Exception {
        Path sourcePath = Path.of(options.get("--complete-report")).toAbsolutePath().normalize();
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        var source = mapper.readValue(sourceBytes, RecoveryBenchmarkReport.class);
        Object frozenPath = source.environment().get("planSnapshot");
        if (!(frozenPath instanceof String path)) throw new IllegalArgumentException("COMPLETION_FROZEN_PLAN_MISSING");
        var plan = mapper.readValue(Files.readString(Path.of(path)), RecoveryBenchmarkPlan.class);
        var pending = completionSamples(source, plan, mapper);
        Map<String, String> code = codeFingerprints(), configuration = configurationFingerprints();
        verifyCompletionFingerprints(source.environment(), code, configuration);

        String runId = UUID.randomUUID().toString();
        Path directory = Path.of(options.getOrDefault("--report-directory", "reports")).toAbsolutePath();
        Files.createDirectories(directory);
        Path planPath = directory.resolve("recovery-" + runId + "-plan.json");
        Files.writeString(planPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var completionPlan = new RecoveryBenchmarkPlan(plan.seed(), pending);
        Path completionPlanPath = directory.resolve("recovery-" + runId + "-completion-plan.json");
        Files.writeString(completionPlanPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(completionPlan), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path completionJournal = directory.resolve("recovery-" + runId + "-completion-observations.jsonl");
        Files.createFile(completionJournal);
        Map<String, String> completionOptions = new LinkedHashMap<>(options);
        int concurrency = Integer.parseInt(options.getOrDefault("--parallelism", "1"));
        completionOptions.put("--parallelism", Integer.toString(Math.min(concurrency, pending.size())));
        Map<String, Object> completionConfig = new LinkedHashMap<>();
        List<String> limitations = new ArrayList<>(source.limitations());
        List<Case> completed = new ArrayList<>();
        System.out.println("M19D completing " + pending.size() + " unattempted samples from " + sourcePath);
        boolean failed = runLive(completionOptions, runId, completionPlan, mapper, completionConfig, limitations, completed, completionJournal);
        var merged = mergeCompletion(source.cases(), completed);
        Path journal = directory.resolve("recovery-" + runId + "-observations.jsonl");
        Files.createFile(journal);
        for (Case result : merged) Files.writeString(journal, mapper.writeValueAsString(result) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Map<String, Object> config = new LinkedHashMap<>(source.environment());
        config.put("sourceReport", sourcePath.toString()); config.put("sourceReportSha256", sha(sourceBytes)); config.put("sourceRunId", source.runId());
        config.put("sourceCodeSha256", source.environment().get("codeSha256"));
        config.put("codeSha256", code); config.put("configurationSha256", configuration);
        config.put("planSnapshot", planPath.toString()); config.put("observationJournal", journal.toString());
        config.put("completionPlanSnapshot", completionPlanPath.toString()); config.put("completionObservationJournal", completionJournal.toString());
        config.put("priorPreparationObservations", source.cases().stream().filter(c -> !c.attempted()).toList());
        config.put("completionConcurrency", completionConfig.get("concurrency"));
        config.put("realStorageVerified", Boolean.TRUE.equals(completionConfig.get("realStorageVerified")));
        if (!(source.environment().get("environments") instanceof List<?> originalEnvironments)
                || !(completionConfig.get("environments") instanceof List<?> newEnvironments)) throw new IllegalArgumentException("COMPLETION_ENVIRONMENTS_MISSING");
        List<Object> environments = new ArrayList<>(originalEnvironments); environments.addAll(newEnvironments);
        config.put("environments", List.copyOf(environments));
        limitations.add("Audited completion executes only NOT_RUN samples with no previous recovery attempt. Original source report, source hash and preparation observations are retained; all actual attempts and confirmed losses keep their original results.");
        limitations.add("Completion uses the same frozen plan, stateful service/checker class hashes and configuration hashes. The orchestrator entry point adds preparation retries and completion; source and completion orchestrator fingerprints are recorded separately.");
        finish(runId, plan, config, limitations, merged, directory, mapper, failed, true);
    }

    /** Each partition has separate containers, credentials, ports and JVM. A fault cannot hit another partition. */
    private static boolean runLive(Map<String, String> options, String runId, RecoveryBenchmarkPlan plan, ObjectMapper mapper,
                                   Map<String, Object> config, List<String> limitations, List<Case> cases, Path journal) throws Exception {
        int parallelism = Integer.parseInt(options.getOrDefault("--parallelism", "1"));
        int timeoutSeconds = Integer.parseInt(options.getOrDefault("--recovery-timeout-seconds", "90"));
        if (parallelism < 1 || parallelism > 8 || parallelism > plan.samples().size()) throw new IllegalArgumentException("INVALID_PARALLELISM");
        if (timeoutSeconds < 40 || timeoutSeconds > 600) throw new IllegalArgumentException("INVALID_RECOVERY_TIMEOUT");
        RecoveryFaultEnvironment.validateParallelPorts(parallelism);
        config.put("concurrency", parallelism); config.put("recoveryTimeoutSeconds", timeoutSeconds);
        config.put("initialStartupConcurrency", Math.min(2, parallelism));
        Map<Integer, Case> observations = new java.util.concurrent.ConcurrentHashMap<>();
        List<Map<String, Object>> environments = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        var failed = new java.util.concurrent.atomic.AtomicBoolean();
        var started = new java.util.concurrent.atomic.AtomicInteger();
        var completed = new java.util.concurrent.atomic.AtomicInteger();
        Object journalLock = new Object();
        // Initial MySQL creation/Flyway is outside the measurement window. Avoid a startup I/O stampede.
        var startupSlots = new java.util.concurrent.Semaphore(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(parallelism);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int partition = 0; partition < parallelism; partition++) {
                final int slot = partition;
                futures.add(workers.submit(() -> {
                    String environmentId = parallelism == 1 ? runId : UUID.randomUUID().toString();
                    RecoveryFaultEnvironment environment = null;
                    try {
                        environment = new RecoveryFaultEnvironment(environmentId, Path.of("."), mapper, options.containsKey("--keep-environment"), slot);
                        environments.add(environment.description());
                        startupSlots.acquire();
                        try { environment.start(); started.incrementAndGet(); }
                        finally { startupSlots.release(); }
                        var runner = new RecoveryBenchmarkRunner(mapper, environment, Duration.ofSeconds(timeoutSeconds), environmentId);
                        int consecutiveFailures = 0;
                        for (int index = slot; index < plan.samples().size(); index += parallelism) {
                            var sample = plan.samples().get(index);
                            Case result = consecutiveFailures >= 3 ? notRun(sample, "STOPPED_AFTER_THREE_CONSECUTIVE_RECOVERY_ERRORS")
                                    : executeWithPreparationRetries(sample, runner::execute);
                            observations.put(index, result);
                            synchronized (journalLock) { Files.writeString(journal, mapper.writeValueAsString(result) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); }
                            boolean error = List.of(Outcome.RECOVERY_FAILED, Outcome.NOT_RUN).contains(result.evaluation().outcome());
                            consecutiveFailures = error ? consecutiveFailures + 1 : 0;
                            if (error) failed.set(true);
                            System.out.println("M19D progress=" + completed.incrementAndGet() + "/" + plan.samples().size() + " " + sample.caseId()
                                    + " fault=" + sample.fault() + " outcome=" + result.evaluation().outcome() + " recoveryMillis=" + result.recoveryMillis());
                        }
                    }
                    catch (Exception e) { errors.add("ENVIRONMENT_" + environmentId + "_FAILED:" + safeError(e)); failed.set(true); }
                    finally {
                        if (environment != null) try { environment.close(); }
                        catch (Exception e) { errors.add("ENVIRONMENT_" + environmentId + "_CLEANUP_FAILED:" + safeError(e)); failed.set(true); }
                    }
                }));
            }
            for (var future : futures) future.get();
        } finally { workers.shutdownNow(); }
        config.put("realStorageVerified", started.get() == parallelism);
        config.put("environments", environments.stream().sorted(java.util.Comparator.comparing(e -> e.get("environmentRunId").toString())).toList());
        limitations.addAll(errors);
        for (int index = 0; index < plan.samples().size(); index++) {
            Case result = observations.get(index);
            if (result == null) {
                result = notRun(plan.samples().get(index), "LIVE_ENVIRONMENT_UNAVAILABLE_OR_RUN_INTERRUPTED");
                Files.writeString(journal, mapper.writeValueAsString(result) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                failed.set(true);
            }
            cases.add(result);
        }
        return failed.get();
    }
}
