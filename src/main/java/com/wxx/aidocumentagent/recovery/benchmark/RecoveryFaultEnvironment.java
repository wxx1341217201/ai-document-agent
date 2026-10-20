package com.wxx.aidocumentagent.recovery.benchmark;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns only the fresh Compose project and child Process objects created by this run. */
final class RecoveryFaultEnvironment implements AutoCloseable {
    private final ObjectMapper mapper;
    private final Path root, runtime, compose;
    private final Map<String, String> environment = new HashMap<>();
    private final String project, runId;
    private final boolean keepEnvironment;
    private boolean composeStarted;
    private Process node;
    private JsonNode info;
    private int nodeNumber, commandNumber;

    RecoveryFaultEnvironment(String runId, Path root, ObjectMapper mapper, boolean keepEnvironment) throws IOException {
        this(runId, root, mapper, keepEnvironment, 0);
    }
    RecoveryFaultEnvironment(String runId, Path root, ObjectMapper mapper, boolean keepEnvironment, int portOffset) throws IOException {
        this.runId = UUID.fromString(runId).toString(); this.root = root.toAbsolutePath().normalize(); this.mapper = mapper;
        this.runtime = this.root.resolve("target/recovery-benchmark/" + this.runId).normalize();
        if (!runtime.startsWith(this.root.resolve("target/recovery-benchmark"))) throw new IllegalArgumentException("INVALID_RUNTIME_PATH");
        Files.createDirectories(runtime);
        compose = this.root.resolve("benchmarks/recovery/compose.yaml");
        project = "m19d-" + this.runId.replace("-", "").substring(0, 20);
        this.keepEnvironment = keepEnvironment;
        for (String key : List.of("M19D_DB_PASSWORD", "M19D_MYSQL_ROOT_PASSWORD", "M19D_REDIS_PASSWORD", "M19D_ACCESS_TOKEN")) {
            String provided = System.getenv(key);
            environment.put(key, provided == null || provided.isBlank() ? secret() : provided);
        }
        environment.put("M19D_RUN_ID", this.runId);
        environment.put("M19D_RUNTIME_DIRECTORY", runtime.toString());
        environment.put("M19D_MYSQL_PORT", Integer.toString(Integer.parseInt(isolatedPort("M19D_MYSQL_PORT", 13307)) + portOffset));
        environment.put("M19D_REDIS_PORT", Integer.toString(Integer.parseInt(isolatedPort("M19D_REDIS_PORT", 16380)) + portOffset));
        validatePortRanges(Integer.parseInt(environment.get("M19D_MYSQL_PORT")), Integer.parseInt(environment.get("M19D_REDIS_PORT")), 1);
        if (environment.get("M19D_MYSQL_PORT").equals(environment.get("M19D_REDIS_PORT"))) throw new IllegalArgumentException("PORTS_MUST_DIFFER");
    }
    private static String secret() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return java.util.HexFormat.of().formatHex(bytes); }
    private static String isolatedPort(String key, int fallback) {
        int port = System.getenv(key) == null ? fallback : Integer.parseInt(System.getenv(key));
        if (port < 10000 || port > 60000) throw new IllegalArgumentException("BENCHMARK_PORT_OUT_OF_RANGE");
        return Integer.toString(port);
    }
    static void validateParallelPorts(int count) {
        validatePortRanges(Integer.parseInt(isolatedPort("M19D_MYSQL_PORT", 13307)), Integer.parseInt(isolatedPort("M19D_REDIS_PORT", 16380)), count);
    }
    static void validatePortRanges(int mysqlBase, int redisBase, int count) {
        java.util.Set<Integer> ports = new java.util.HashSet<>();
        for (int slot = 0; slot < count; slot++) {
            int mysql = mysqlBase + slot, redis = redisBase + slot;
            if (mysql < 10000 || redis < 10000 || mysql > 60000 || redis > 60000) throw new IllegalArgumentException("BENCHMARK_PORT_OUT_OF_RANGE");
            if (!ports.add(mysql) || !ports.add(redis)) throw new IllegalArgumentException("PARALLEL_BENCHMARK_PORTS_OVERLAP");
        }
    }
    void start() throws Exception {
        if (!Files.isRegularFile(compose)) throw new IOException("BENCHMARK_COMPOSE_MISSING");
        command(List.of("docker", "version", "--format", "{{.Server.Version}}"), Duration.ofSeconds(10));
        for (String port : List.of(environment.get("M19D_MYSQL_PORT"), environment.get("M19D_REDIS_PORT"))) {
            try (Socket socket = new Socket()) {
                try { socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 500); }
                catch (IOException unused) { continue; }
                throw new IOException("BENCHMARK_PORT_ALREADY_IN_USE");
            }
        }
        composeStarted = true;
        compose(List.of("up", "-d", "--wait", "--wait-timeout", "120"), Duration.ofSeconds(180));
        verifyContainer("mysql"); verifyContainer("redis");
        startNode();
    }
    private JsonNode verifyContainer(String service) throws Exception {
        String id = compose(List.of("ps", "-q", service), Duration.ofSeconds(90)).strip();
        if (!id.matches("[a-f0-9]{12,64}")) throw new IOException("BENCHMARK_CONTAINER_ID_UNVERIFIED");
        // Inspect only state/labels. Docker's full Config.Env includes credentials and must not enter logs.
        // Separate JSON expressions avoid Windows native argument handling stripping embedded JSON key quotes.
        JsonNode state = mapper.readTree(command(List.of("docker", "inspect", "--format", "{{json .State}}", id), Duration.ofSeconds(90)));
        JsonNode labels = mapper.readTree(command(List.of("docker", "inspect", "--format", "{{json .Config.Labels}}", id), Duration.ofSeconds(90)));
        JsonNode container = mapper.valueToTree(Map.of("Id", id, "State", state, "Labels", labels));
        if (!runId.equals(labels.path("benchmark.run-id").asText()) || !"M19D".equals(labels.path("benchmark.module").asText())
                || !project.equals(labels.path("com.docker.compose.project").asText())) throw new IOException("BENCHMARK_CONTAINER_OWNERSHIP_MISMATCH");
        return container;
    }
    Map<String, Object> restartRedis() throws Exception {
        JsonNode before = verifyContainer("redis");
        compose(List.of("restart", "--timeout", "2", "redis"), Duration.ofSeconds(60));
        compose(List.of("up", "-d", "--wait", "--wait-timeout", "60", "redis"), Duration.ofSeconds(90));
        JsonNode after = verifyContainer("redis");
        String oldStart = before.path("State").path("StartedAt").asText(), newStart = after.path("State").path("StartedAt").asText();
        if (oldStart.equals(newStart) || !after.path("State").path("Running").asBoolean()) throw new IOException("REDIS_RESTART_NOT_VERIFIED");
        return Map.of("composeProject", project, "containerId", after.path("Id").asText(), "startedBefore", oldStart, "startedAfter", newStart);
    }
    Map<String, Object> killNode() throws Exception {
        if (node == null || !node.isAlive() || node.pid() != info.path("pid").asLong()) throw new IOException("OWNED_NODE_NOT_RUNNING");
        long pid = node.pid(); node.destroyForcibly();
        if (!node.waitFor(10, TimeUnit.SECONDS) || node.isAlive()) throw new IOException("NODE_KILL_NOT_VERIFIED");
        return Map.of("terminatedPid", pid, "terminated", true);
    }
    void startNode() throws Exception {
        if (node != null && node.isAlive()) throw new IllegalStateException("NODE_ALREADY_RUNNING");
        Path ready = runtime.resolve("node-ready.json"); Files.deleteIfExists(ready);
        Path arguments = runtime.resolve("node-" + (++nodeNumber) + ".args");
        String url = "jdbc:mysql://127.0.0.1:" + environment.get("M19D_MYSQL_PORT")
                + "/ai_document_agent_m19d?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&connectTimeout=10000&socketTimeout=15000";
        List<String> args = List.of("-Xms64m", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=" + runtime, "-cp", System.getProperty("java.class.path"),
                RecoveryBenchmarkNode.class.getName(), "--spring.datasource.url=" + url, "--spring.datasource.username=m19d",
                "--spring.datasource.password=${M19D_DB_PASSWORD}", "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + environment.get("M19D_REDIS_PORT"), "--spring.data.redis.password=${M19D_REDIS_PASSWORD}",
                "--server.address=127.0.0.1", "--server.port=0", "--app.progress.subscription-token=${M19D_ACCESS_TOKEN}");
        Files.write(arguments, args.stream().map(RecoveryFaultEnvironment::javaArgument).toList(), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "@" + arguments);
        builder.directory(root.toFile());
        // User-wide Spring environment settings must not redirect this child to the main application's database.
        builder.environment().keySet().removeIf(key -> key.startsWith("SPRING_") || key.startsWith("APP_"));
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(runtime.resolve("node-" + nodeNumber + ".log").toFile());
        node = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!Files.exists(ready)) {
            if (!node.isAlive()) throw new IOException("NODE_START_FAILED");
            if (System.nanoTime() >= deadline) throw new IOException("NODE_START_TIMEOUT");
            Thread.sleep(100);
        }
        JsonNode next = mapper.readTree(Files.readString(ready));
        if (!runId.equals(next.path("runId").asText()) || next.path("pid").asLong() != node.pid()
                || !node.isAlive() || next.path("port").asInt() <= 0) throw new IOException("NODE_IDENTITY_UNVERIFIED");
        if (info != null && info.path("knowledgeBaseId").asLong() != next.path("knowledgeBaseId").asLong()) throw new IOException("FIXTURE_KNOWLEDGE_BASE_CHANGED");
        info = next;
    }
    private String compose(List<String> args, Duration timeout) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "--project-name", project, "--file", compose.toString()));
        command.addAll(args); return command(command, timeout);
    }
    private String command(List<String> args, Duration timeout) throws Exception {
        Path output = runtime.resolve("command-" + (++commandNumber) + ".log");
        ProcessBuilder builder = new ProcessBuilder(args).directory(root.toFile());
        builder.environment().putAll(environment);
        Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); throw new IOException("COMMAND_TIMEOUT");
        }
        if (process.exitValue() != 0) throw new IOException("COMMAND_FAILED_EXIT_" + process.exitValue());
        return Files.readString(output, StandardCharsets.UTF_8);
    }
    static String javaArgument(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    long knowledgeBaseId() { return info.path("knowledgeBaseId").asLong(); }
    long decoyKnowledgeBaseId() { return info.path("decoyKnowledgeBaseId").asLong(); }
    long nodePid() { return node.pid(); }
    String baseUrl() { return "http://127.0.0.1:" + info.path("port").asInt(); }
    String token() { return environment.get("M19D_ACCESS_TOKEN"); }
    Path runtime() { return runtime; }
    void release(String id) throws IOException {
        String canonical = UUID.fromString(id).toString();
        if (!canonical.equalsIgnoreCase(id)) throw new IllegalArgumentException("INVALID_EXECUTION_ID");
        Files.writeString(runtime.resolve(canonical + ".release"), "RELEASE\n", StandardCharsets.UTF_8);
    }
    Map<String, Object> description() {
        return Map.ofEntries(Map.entry("profile", "CONTROLLED_AGENT_REAL_STORAGE_PROCESS_FAULTS"),
                Map.entry("composeProject", project), Map.entry("environmentRunId", runId),
                Map.entry("mysqlImage", "mysql:8.4"), Map.entry("redisImage", "redis:8-alpine"),
                Map.entry("mysqlPort", environment.get("M19D_MYSQL_PORT")), Map.entry("redisPort", environment.get("M19D_REDIS_PORT")),
                Map.entry("runtimeDirectory", runtime.toString()),
                Map.entry("workerTopology", "M17 local ThreadPoolTaskExecutor in the owned application JVM"),
                Map.entry("effectiveClaimLeaseSeconds", 45), Map.entry("agentDeadlineSeconds", 15),
                Map.entry("jdbcConnectTimeoutSeconds", 10), Map.entry("jdbcSocketTimeoutSeconds", 15),
                Map.entry("hikariConnectionTimeoutSeconds", 10), Map.entry("flywayConnectRetries", 3),
                Map.entry("containerOwnershipQueryTimeoutSeconds", 90),
                Map.entry("restRequestTimeoutSeconds", 30),
                Map.entry("childJvmMaxHeapMiB", 256), Map.entry("childJvmActiveProcessors", 2));
    }
    @Override public void close() throws Exception {
        if (node != null && node.isAlive()) { node.destroyForcibly(); if (!node.waitFor(10, TimeUnit.SECONDS)) throw new IOException("OWNED_NODE_CLEANUP_FAILED"); }
        if (composeStarted && !keepEnvironment) compose(List.of("down", "--timeout", "5"), Duration.ofSeconds(60));
    }
}
