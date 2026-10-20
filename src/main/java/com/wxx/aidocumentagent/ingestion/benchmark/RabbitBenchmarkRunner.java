package com.wxx.aidocumentagent.ingestion.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One isolated, sequential hardware-controlled experiment per matrix row. No shared service access. */
public final class RabbitBenchmarkRunner {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String runId = UUID.randomUUID().toString();
    private final Options options;
    private final Path results;
    private final Path runtime;
    private final Map<String, Object> corpus;
    private final List<Environment> active = new CopyOnWriteArrayList<>();

    private RabbitBenchmarkRunner(Options options) throws Exception {
        this.options = options;
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        results = options.output.resolve("run-" + stamp + "-" + runId.substring(0, 8)).toAbsolutePath();
        runtime = Path.of("target/rabbitmq-benchmark", runId).toAbsolutePath();
        Files.createDirectories(results);
        Files.createDirectories(runtime);
        corpus = RabbitBenchmarkCorpus.generate(Path.of("benchmarks/rabbitmq/data"), mapper);
    }

    private List<Config> matrix() {
        var matrix = new ArrayList<Config>();
        for (int batch : options.batches) {
            matrix.add(new Config("sync", batch, 1));
            for (int workers : options.consumers) matrix.add(new Config("async", batch, workers));
        }
        Collections.shuffle(matrix, new Random(options.seed));
        return matrix;
    }

    private int run() throws Exception {
        List<Config> matrix = matrix();
        var hardware = new LinkedHashMap<String, Object>();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        hardware.put("os", os.getName()); hardware.put("arch", os.getArch());
        hardware.put("hostProcessors", os.getAvailableProcessors()); hardware.put("hostMemoryBytes", os.getTotalMemorySize());
        hardware.put("javaVersion", System.getProperty("java.version"));
        hardware.put("nodeActiveProcessors", 4); hardware.put("nodeMaxHeapMiB", 512);
        hardware.put("mysqlCpuLimit", 2); hardware.put("rabbitCpuLimit", 2);
        hardware.put("mysqlMemoryLimitMiB", 1024); hardware.put("rabbitMemoryLimitMiB", 1024);
        var plan = new LinkedHashMap<String, Object>();
        plan.put("schema", "m19e-plan-v1"); plan.put("runId", runId); plan.put("createdAt", Instant.now().toString());
        plan.put("profile", "DETERMINISTIC_LOCAL_INDEX_REAL_MYSQL_RABBITMQ");
        plan.put("options", options); plan.put("hardware", hardware); plan.put("matrix", matrix); plan.put("corpus", corpus);
        plan.put("fingerprints", fingerprints());
        writeJson(results.resolve("plan.json"), plan);
        if (!options.live) {
            // Also validate expected chunk counts using the actual production parser and chunker.
            var parser = new com.wxx.aidocumentagent.document.parser.TxtDocumentParser();
            var chunker = new com.wxx.aidocumentagent.chunking.FixedWindowChunkingStrategy();
            for (int i = 0; i < 100; i++) {
                Path path = Path.of("benchmarks/rabbitmq/data/doc-%03d.txt".formatted(i));
                var parsed = parser.parse(new com.wxx.aidocumentagent.document.parser.DocumentSource(
                        com.wxx.aidocumentagent.document.parser.DocumentType.TXT, path.getFileName().toString(), () -> Files.newInputStream(path)));
                int chunks = chunker.split(parsed, new com.wxx.aidocumentagent.chunking.ChunkingOptions(
                        com.wxx.aidocumentagent.chunking.ChunkingUnit.CHARACTER, 512, 0)).size();
                if (chunks != (i % 2 == 0 ? 200 : 20)) throw new IllegalStateException("Unexpected actual corpus chunk count");
            }
            writeJson(results.resolve("validation.json"), Map.of("status", "VALIDATED", "corpusDocuments", 100,
                    "actualAverageChunks", 110, "matrixRows", matrix.size(), "performanceEvaluated", false));
            Files.writeString(results.resolve("report.md"), "# M19E 离线校验\n\n100 份固定文档的生产解析/切分与 SHA-256 校验通过；平均 110 chunks、56,320 bytes，长文档 50%。\n\n"
                    + "矩阵 " + matrix.size() + " 行。未连接 RabbitMQ/MySQL，没有性能实测数据，目标 NOT_EVALUABLE。\n", StandardCharsets.UTF_8);
            System.out.println("Validated corpus and " + matrix.size() + " matrix rows: " + results);
            return 0;
        }
        var rows = new ArrayList<Map<String, Object>>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Environment environment : active) try { environment.close(); } catch (Exception ignored) { }
        }, "m19e-cleanup"));
        for (Config config : matrix) {
            System.out.println("Running " + config.id() + " (20 outstanding document submissions, warmup=" + options.warmupDocuments + ")");
            var row = new LinkedHashMap<String, Object>();
            row.put("config", config);
            Environment environment = new Environment(config);
            active.add(environment);
            try {
                environment.start();
                row.put("environment", environment.description());
                runPhase(environment, "warmup", options.warmupDocuments, 0, false);
                Phase phase = runPhase(environment, "measure", 100, options.durationSeconds, true);
                JsonNode exported = environment.request("GET", "/benchmark/export", null);
                var raw = mapper.convertValue(exported, LinkedHashMap.class);
                raw.put("config", config); raw.put("measurementStartedAt", phase.startedAt);
                raw.put("admissionStoppedAt", phase.admissionStoppedAt);
                raw.put("clientObservations", phase.observations); raw.put("samples", phase.samples);
                raw.put("profile", plan.get("profile")); raw.put("environment", environment.description());
                raw.put("measurementFinishedAt", RabbitBenchmarkMetrics.now());
                JsonNode tree = mapper.valueToTree(raw);
                var evaluated = RabbitBenchmarkEvaluator.evaluate(tree, 60, options.errorThreshold);
                row.put("evaluation", evaluated);
                writeJson(results.resolve(config.id() + ".json"), raw);
                RabbitBenchmarkReport.writeCsv(results, config.id(), tree, mapper.valueToTree(evaluated));
                System.out.println(config.id() + ": valid=" + evaluated.get("valid") + ", errors=" + evaluated.get("errors")
                        + ", peak=" + evaluated.get("peakStableWindow"));
            } catch (Exception e) {
                row.put("error", e.getClass().getSimpleName() + ": " + safeMessage(e));
                row.put("environment", environment.description());
                System.out.println(config.id() + " failed; diagnostic logs: " + environment.directory);
            } finally {
                try { environment.close(); row.put("cleanup", "COMPLETED"); }
                catch (Exception e) { row.put("cleanup", "FAILED: " + e.getClass().getSimpleName()); }
                active.remove(environment);
            }
            rows.add(row);
            writeJson(results.resolve("summary.json"), Map.of("plan", plan, "rows", rows));
            RabbitBenchmarkReport.writeReport(results, mapper.valueToTree(plan), mapper.valueToTree(rows));
        }
        JsonNode summary = RabbitBenchmarkReport.summary(mapper.valueToTree(plan), mapper.valueToTree(rows));
        writeJson(results.resolve("summary.json"), Map.of("plan", plan, "rows", rows, "comparison", summary));
        RabbitBenchmarkReport.writeReport(results, mapper.valueToTree(plan), mapper.valueToTree(rows));
        System.out.println("M19E results: " + results + "; " + summary);
        boolean usable = rows.stream().allMatch(r -> r.containsKey("evaluation")
                && ((Map<?, ?>) r.get("evaluation")).get("valid").equals(true) && "COMPLETED".equals(r.get("cleanup")));
        if (options.requireTargets) usable &= summary.path("throughputTarget").asString().equals("MET")
                && summary.path("waitTarget").asString().equals("MET");
        return usable ? 0 : 2;
    }

    private Phase runPhase(Environment environment, String name, int minimumDocuments, int duration, boolean record) throws Exception {
        double started = environment.request("POST", "/benchmark/begin?phase=" + name, new byte[0]).path("origin").asDouble();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(duration);
        var observations = new CopyOnWriteArrayList<Map<String, Object>>();
        var samples = new ArrayList<JsonNode>();
        AtomicInteger next = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(options.submitConcurrency);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int worker = 0; worker < options.submitConcurrency; worker++) futures.add(pool.submit(() -> {
            while (true) {
                int sequence = next.getAndIncrement();
                if (sequence >= minimumDocuments && System.nanoTime() >= deadline) break;
                var observation = new LinkedHashMap<String, Object>();
                String id = "doc-%03d".formatted(sequence % 100);
                observation.put("sequence", sequence); observation.put("corpusId", id); observation.put("round", sequence / 100);
                observation.put("startedAt", RabbitBenchmarkMetrics.now());
                long before = System.nanoTime();
                try {
                    byte[] content = Files.readAllBytes(Path.of("benchmarks/rabbitmq/data", id + ".txt"));
                    String boundary = "m19e-" + UUID.randomUUID();
                    byte[] body = multipart(boundary, id + ".txt", content);
                    JsonNode submitted = environment.upload("/benchmark/documents?corpusId=" + id + "&round=" + sequence / 100, body, boundary);
                    observation.put("httpLatencyMs", (System.nanoTime() - before) / 1_000_000.0);
                    long documentId = submitted.path("documentId").asLong();
                    observation.put("documentId", documentId);
                    long waitLimit = before + TimeUnit.SECONDS.toNanos(180);
                    while (true) {
                        JsonNode status = environment.request("GET", "/benchmark/documents/" + documentId, null);
                        if (status.path("ready").asBoolean()) break;
                        if (status.path("status").asString().equals("FAILED")) throw new IllegalStateException("DOCUMENT_FAILED");
                        if (System.nanoTime() > waitLimit) throw new IOException("DOCUMENT_READY timeout");
                        Thread.sleep(250);
                    }
                    observation.put("success", true);
                } catch (Exception e) {
                    observation.put("success", false); observation.put("error", e.getClass().getSimpleName() + ": " + safeMessage(e));
                }
                observation.put("finishedAt", RabbitBenchmarkMetrics.now());
                observations.add(observation);
                if (record) synchronized (observations) {
                    try { Files.writeString(results.resolve(environment.config.id() + "-client.jsonl"),
                            mapper.writeValueAsString(observation) + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }
                // Failures remain in the denominator; avoid an uncontrolled flood if the environment fails.
                if (!(boolean) observation.get("success")) break;
            }
        }));
        pool.shutdown();
        double admissionStopped = 0;
        long lastProgress = System.nanoTime();
        try {
            while (!pool.isTerminated()) {
                if (admissionStopped == 0 && System.nanoTime() >= deadline && next.get() >= minimumDocuments)
                    admissionStopped = RabbitBenchmarkMetrics.now();
                if (record) {
                    var sample = mapper.convertValue(environment.request("GET", "/benchmark/sample", null), LinkedHashMap.class);
                    if (environment.config.mode.equals("async")) sample.putAll(environment.brokerDepths());
                    else sample.put("rabbitQueues", List.of());
                    JsonNode tree = mapper.valueToTree(sample);
                    samples.add(tree);
                    Files.writeString(results.resolve(environment.config.id() + "-samples.jsonl"), mapper.writeValueAsString(tree) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(30)) {
                        System.out.println(environment.config.id() + ": ready=" + sample.get("ready")
                                + ", batches=" + sample.get("completedBatches") + ", backlog=" + sample.get("batchBacklog"));
                        lastProgress = System.nanoTime();
                    }
                }
                pool.awaitTermination(1, TimeUnit.SECONDS);
            }
            for (var future : futures) future.get();
        } finally { pool.shutdownNow(); }
        if (admissionStopped == 0) admissionStopped = RabbitBenchmarkMetrics.now();
        if (!record && observations.stream().anyMatch(o -> !(boolean) o.get("success")))
            throw new IllegalStateException("Warmup failed; inspect node logs");
        return new Phase(started, admissionStopped, observations, samples);
    }

    private static byte[] multipart(String boundary, String filename, byte[] content) throws IOException {
        var output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(content);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private class Environment implements AutoCloseable {
        final Config config;
        final String environmentId = UUID.randomUUID().toString();
        final String project = "m19e-" + environmentId;
        final Path directory;
        final Map<String, String> variables = new LinkedHashMap<>();
        final String token = secret();
        final String rabbitPassword = secret();
        Process node;
        String address;
        int mysqlPort, rabbitPort, managementPort;
        boolean attemptedCompose;
        boolean closed;

        Environment(Config config) throws IOException {
            this.config = config;
            directory = runtime.resolve(config.id());
            Files.createDirectories(directory);
        }
        void start() throws Exception {
            mysqlPort = freePort(); rabbitPort = freePort(); managementPort = freePort();
            while (rabbitPort == mysqlPort) rabbitPort = freePort();
            while (managementPort == rabbitPort || managementPort == mysqlPort) managementPort = freePort();
            variables.put("M19E_RUN_ID", environmentId);
            variables.put("M19E_MYSQL_PORT", "" + mysqlPort);
            variables.put("M19E_RABBIT_PORT", "" + rabbitPort);
            variables.put("M19E_RABBIT_MANAGEMENT_PORT", "" + managementPort);
            variables.put("M19E_DB_PASSWORD", secret());
            variables.put("M19E_MYSQL_ROOT_PASSWORD", secret());
            variables.put("M19E_RABBIT_PASSWORD", rabbitPassword);
            variables.put("M19E_ACCESS_TOKEN", token);
            attemptedCompose = true;
            command(compose("up", "-d", "--wait", "--wait-timeout", "120"), "compose-up.log", 180);
            verifyOwnership();
            command(List.of("docker", "image", "inspect", "--format", "{{.Id}}|{{.Architecture}}|{{.Os}}", "mysql:8.4", "rabbitmq:4.1-management"), "image-identities.log", 30);
            var args = new ArrayList<>(List.of(javaExecutable(), "-Xms128m", "-Xmx512m", "-XX:ActiveProcessorCount=4",
                    "-Duser.timezone=UTC", "-cp", System.getProperty("java.class.path"), RabbitBenchmarkNode.class.getName(),
                    "--app.benchmark.rabbitmq.mode=" + config.mode,
                    "--app.benchmark.rabbitmq.run-id=" + environmentId,
                    "--app.benchmark.rabbitmq.runtime-directory=" + directory,
                    "--app.benchmark.rabbitmq.chunk-delay-ms=" + options.chunkDelayMs,
                    "--app.benchmark.rabbitmq.batch-delay-ms=" + options.batchDelayMs,
                    "--app.ingestion.batch-size=" + config.batchSize,
                    "--app.ingestion.listener-concurrency=" + config.consumers,
                    "--app.ingestion.listener-max-concurrency=" + config.consumers,
                    "--app.ingestion.prefetch=" + options.prefetch));
            Path argFile = directory.resolve("node.args");
            Files.write(argFile, args.subList(1, args.size()).stream().map(RabbitBenchmarkRunner::javaArgument).toList(), StandardCharsets.UTF_8);
            var builder = process(List.of(args.getFirst(), "@" + argFile));
            builder.redirectOutput(directory.resolve("node.stdout.log").toFile());
            builder.redirectError(directory.resolve("node.stderr.log").toFile());
            node = builder.start();
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            Path ready = directory.resolve("node-ready.json");
            while (!Files.exists(ready)) {
                if (!node.isAlive() || System.nanoTime() > timeout) throw new IOException("Node startup failed or timed out");
                Thread.sleep(200);
            }
            JsonNode identity = mapper.readTree(Files.readString(ready));
            if (!identity.path("runId").asString().equals(environmentId) || identity.path("pid").asLong() != node.pid())
                throw new IOException("Node ownership mismatch");
            address = "http://127.0.0.1:" + identity.path("port").asInt();
        }
        Map<String, Object> description() {
            var description = new LinkedHashMap<String, Object>();
            description.put("runId", environmentId); description.put("composeProject", project);
            description.put("runtimeDirectory", directory.toString());
            description.put("mysqlPort", mysqlPort); description.put("rabbitPort", rabbitPort);
            description.put("managementPort", managementPort); description.put("nodePid", node == null ? null : node.pid());
            description.put("prefetchPerConsumer", options.prefetch);
            description.put("documentConsumers", config.mode.equals("async") ? config.consumers : 0);
            description.put("batchConsumers", config.mode.equals("async") ? config.consumers : 0);
            return description;
        }
        JsonNode request(String method, String route, byte[] body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(address + route)).timeout(Duration.ofSeconds(180))
                    .header("X-M19E-Token", token).method(method,
                            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build();
            return response(request);
        }
        JsonNode upload(String route, byte[] body, String boundary) throws Exception {
            return response(HttpRequest.newBuilder(URI.create(address + route)).timeout(Duration.ofSeconds(180))
                    .header("X-M19E-Token", token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
        }
        JsonNode response(HttpRequest request) throws Exception {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new IOException("HTTP " + response.statusCode());
            return mapper.readTree(response.body());
        }
        Map<String, Object> brokerDepths() throws Exception {
            String basic = Base64.getEncoder().encodeToString(("m19e:" + rabbitPassword).getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/api/queues"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Basic " + basic).GET().build();
            JsonNode queues = response(request);
            var depths = new ArrayList<Map<String, Object>>();
            for (JsonNode queue : queues) depths.add(Map.of("name", queue.path("name").asString(),
                    "ready", queue.path("messages_ready").asLong(), "unacknowledged", queue.path("messages_unacknowledged").asLong(),
                    "consumers", queue.path("consumers").asLong()));
            return Map.of("rabbitQueues", depths);
        }
        List<String> compose(String... tail) {
            var command = new ArrayList<>(List.of("docker", "compose", "-f", "benchmarks/rabbitmq/compose.yaml", "-p", project));
            command.addAll(List.of(tail));
            return command;
        }
        ProcessBuilder process(List<String> command) {
            var builder = new ProcessBuilder(command);
            builder.environment().keySet().removeIf(k -> k.startsWith("SPRING_") || k.startsWith("APP_")
                    || k.startsWith("M19E_") || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(k));
            builder.environment().putAll(variables);
            return builder;
        }
        String command(List<String> command, String log, int timeout) throws Exception {
            Path output = directory.resolve(log);
            Process process = process(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Environment command timed out: " + log); }
            if (process.exitValue() != 0) throw new IOException("Environment command failed: " + log);
            return Files.readString(output);
        }
        void verifyOwnership() throws Exception {
            String ids = command(compose("ps", "-aq"), "owned-containers.log", 30);
            for (String id : ids.split("\\R")) if (!id.isBlank()) {
                JsonNode labels = mapper.readTree(command(List.of("docker", "inspect", "--format", "{{json .Config.Labels}}", id),
                        "ownership-" + id.substring(0, 12) + ".log", 30));
                if (!labels.path("benchmark.module").asString().equals("M19E")
                        || !labels.path("benchmark.run-id").asString().equals(environmentId)
                        || !labels.path("com.docker.compose.project").asString().equals(project))
                    throw new IOException("Container ownership mismatch");
            }
        }
        @Override public synchronized void close() throws Exception {
            if (closed) return;
            if (node != null && node.isAlive()) {
                node.destroy();
                if (!node.waitFor(10, TimeUnit.SECONDS)) { node.destroyForcibly(); node.waitFor(10, TimeUnit.SECONDS); }
                if (node.isAlive()) throw new IOException("Node cleanup failed");
            }
            if (attemptedCompose) {
                verifyOwnership();
                command(compose("down", "-v"), "compose-down.log", 60);
                if (!command(compose("ps", "-aq"), "remaining-containers.log", 30).isBlank()) throw new IOException("Containers remain");
            }
            closed = true;
        }
    }

    private Map<String, String> fingerprints() throws Exception {
        var values = new java.util.TreeMap<String, String>();
        for (Path root : List.of(Path.of("src/main/java/com/wxx/aidocumentagent/ingestion"),
                Path.of("src/main/java/com/wxx/aidocumentagent/document"), Path.of("src/main/java/com/wxx/aidocumentagent/chunking"),
                Path.of("src/main/resources/db/migration"))) try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) values.put(path.toString(), hash(Files.readAllBytes(path)));
        }
        for (Path path : List.of(Path.of("src/main/resources/application-rabbitmq-benchmark.yml"),
                Path.of("src/main/resources/application-rabbitmq-benchmark-sync.yml"),
                Path.of("src/main/resources/application-rabbitmq-benchmark-async.yml"), Path.of("benchmarks/rabbitmq/compose.yaml")))
            values.put(path.toString(), hash(Files.readAllBytes(path)));
        try (var files = Files.walk(Path.of("target/classes"))) {
            for (Path path : files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class")).toList())
                values.put(path.toString(), hash(Files.readAllBytes(path)));
        }
        return values;
    }
    static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private void writeJson(Path path, Object data) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data), StandardCharsets.UTF_8);
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private static String secret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
    private static String javaExecutable() { return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(); }
    private static String javaArgument(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String safeMessage(Exception exception) {
        if (exception instanceof IOException) return exception.getMessage() == null ? "I/O failure" : exception.getMessage();
        return "Inspect runtime diagnostic log";
    }
    record Config(String mode, int batchSize, int consumers) {
        String id() { return mode + "-b" + batchSize + "-c" + consumers; }
    }
    record Phase(double startedAt, double admissionStoppedAt, List<Map<String, Object>> observations, List<JsonNode> samples) { }
    record Options(boolean live, List<Integer> batches, List<Integer> consumers, int durationSeconds, int warmupDocuments,
                   int submitConcurrency, int prefetch, int chunkDelayMs, int batchDelayMs, long seed,
                   double errorThreshold, Path output, boolean requireTargets) {
        static Options parse(String[] args) {
            var values = new HashMapLike();
            for (String arg : args) {
                if (!arg.startsWith("--")) throw new IllegalArgumentException("Invalid option");
                String[] pair = arg.substring(2).split("=", 2);
                if (!List.of("live", "batch-sizes", "consumers", "duration-seconds", "warmup-documents", "submit-concurrency",
                        "prefetch", "chunk-delay-ms", "batch-delay-ms", "seed", "error-threshold", "output-directory", "require-targets").contains(pair[0]))
                    throw new IllegalArgumentException("Unknown benchmark option");
                values.put(pair[0], pair.length == 1 ? "true" : pair[1]);
            }
            var options = new Options(Boolean.parseBoolean(values.getOrDefault("live", "false")),
                    integers(values.getOrDefault("batch-sizes", "25,50,100"), List.of(25, 50, 100)),
                    integers(values.getOrDefault("consumers", "1,2,4,8"), List.of(1, 2, 4, 8)),
                    Integer.parseInt(values.getOrDefault("duration-seconds", "90")),
                    Integer.parseInt(values.getOrDefault("warmup-documents", "20")),
                    Integer.parseInt(values.getOrDefault("submit-concurrency", "20")),
                    Integer.parseInt(values.getOrDefault("prefetch", "2")),
                    Integer.parseInt(values.getOrDefault("chunk-delay-ms", "2")),
                    Integer.parseInt(values.getOrDefault("batch-delay-ms", "2")),
                    Long.parseLong(values.getOrDefault("seed", "20261011")),
                    Double.parseDouble(values.getOrDefault("error-threshold", "0.01")),
                    Path.of(values.getOrDefault("output-directory", "benchmarks/rabbitmq/results")),
                    Boolean.parseBoolean(values.getOrDefault("require-targets", "false")));
            if (options.durationSeconds < 1 || options.durationSeconds > 3600 || options.warmupDocuments < 2
                    || options.warmupDocuments > 100 || options.submitConcurrency != 20 || options.prefetch < 1
                    || options.prefetch > 100 || options.chunkDelayMs < 0 || options.chunkDelayMs > 50
                    || options.batchDelayMs < 0 || options.batchDelayMs > 100 || options.errorThreshold < 0
                    || options.errorThreshold > .05) throw new IllegalArgumentException("Invalid benchmark options");
            return options;
        }
        private static List<Integer> integers(String value, List<Integer> allowed) {
            List<Integer> list = java.util.Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
            if (list.isEmpty() || !allowed.containsAll(list) || list.stream().distinct().count() != list.size())
                throw new IllegalArgumentException("Invalid matrix options");
            return list;
        }
    }
    private static class HashMapLike extends java.util.HashMap<String, String> { }
    public static void main(String[] args) throws Exception { System.exit(new RabbitBenchmarkRunner(Options.parse(args)).run()); }
}
