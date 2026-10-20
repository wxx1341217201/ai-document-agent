package com.wxx.aidocumentagent.retrieval.benchmark;

import java.io.ByteArrayInputStream;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.wxx.aidocumentagent.AiDocumentAgentApplication;
import com.wxx.aidocumentagent.agent.*;
import com.wxx.aidocumentagent.chunking.*;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.document.storage.DocumentStorage;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBase;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryCommand;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import com.wxx.aidocumentagent.retrieval.RetrievalQuery;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import com.wxx.aidocumentagent.vector.*;
import com.wxx.aidocumentagent.vector.infrastructure.ClassifyingEmbeddingModel;
import com.wxx.aidocumentagent.vector.infrastructure.QdrantVectorIndex;
import io.qdrant.client.QdrantClient;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Standalone public-data adapter. No component registration or production application changes. */
public final class PublicBenchmarkBridge {
    private final ConfigurableApplicationContext context;
    private final ObjectMapper mapper;
    private final Path prepared;
    private final Path reports;
    private final String manifestSha;
    private final boolean rerank;
    private final int limit;

    private PublicBenchmarkBridge(ConfigurableApplicationContext context) throws Exception {
        this.context = context;
        mapper = context.getBean(ObjectMapper.class);
        prepared = Path.of(property("public.prepared", "benchmarks/public/prepared/cmrc200-hotpot100-seed20261019"));
        reports = Path.of(property("public.reports", "reports/public-benchmark"));
        Files.createDirectories(reports);
        manifestSha = sha(Files.readString(prepared.resolve("manifest.json"), StandardCharsets.UTF_8));
        rerank = Boolean.parseBoolean(property("public.rerank", "true"));
        limit = Integer.parseInt(property("public.limit", "0"));
        for (String name : List.of("app.vector.qdrant.collection-name", "app.keyword.index-name", "app.keyword.alias")) {
            if (!property(name, "").contains("benchmark_public")) throw new IllegalArgumentException("Isolated public benchmark index required: " + name);
        }
    }

    private String property(String key, String fallback) { return context.getEnvironment().getProperty(key, fallback); }
    private static String sha(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private List<JsonNode> rows(String file) throws Exception {
        var result = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(prepared.resolve(file), StandardCharsets.UTF_8)) if (!line.isBlank()) result.add(mapper.readTree(line));
        return result;
    }
    private List<JsonNode> selected(String file) throws Exception {
        var result = new ArrayList<JsonNode>();
        var counts = new HashMap<String, Integer>();
        for (var row : rows(file)) {
            String dataset = row.path("dataset").asText();
            if (limit == 0 || counts.getOrDefault(dataset, 0) < limit) {
                result.add(row); counts.merge(dataset, 1, Integer::sum);
            }
        }
        return result;
    }
    private void write(Path path, Object value) throws Exception {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private String stamp() { return Instant.now().toString().replaceAll("[^0-9TZ]", "") + "-" + UUID.randomUUID().toString().substring(0, 8); }

    private void seed() throws Exception {
        Path bindingPath = prepared.resolve("binding.json");
        if (Files.exists(bindingPath)) throw new IllegalStateException("Binding already exists; use it or prepare a separate new dataset directory");
        var kbRepository = context.getBean(KnowledgeBaseRepository.class);
        var docRepository = context.getBean(DocumentRepository.class);
        var chunkRepository = context.getBean(DocumentChunkRepository.class);
        var storage = context.getBean(DocumentStorage.class);
        var chunking = context.getBean(ChunkingProperties.class);
        var splitter = context.getBean(ChunkingStrategyRegistry.class).strategyFor(chunking.getStrategy());
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var vector = context.getBean(VectorIndex.class);
        var keyword = context.getBean(KeywordIndex.class);
        var vectorProperties = context.getBean(VectorProperties.class);
        var kbIds = new LinkedHashMap<String, Long>();
        var documents = new ArrayList<Map<String, Object>>();
        var corpusChunks = new ArrayList<DocumentChunk>();
        var ownedDocuments = new ArrayList<Document>();
        String runId = stamp();
        Path journalPath = reports.resolve("seed-" + runId + ".jsonl");
        try (var journal = Files.newBufferedWriter(journalPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (var row : rows("corpus.jsonl")) {
                String dataset = row.path("dataset").asText();
                long kbId = kbIds.computeIfAbsent(dataset, name -> transaction.execute(status -> kbRepository.saveAndFlush(
                        KnowledgeBase.create("Public benchmark " + name + " " + runId,
                                "Original public development corpus; CC BY-SA 4.0; manifest=" + manifestSha)).getId()));
                String text = row.path("text").asText();
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (!sha(text).equals(row.path("textSha256").asText())) throw new IllegalStateException("SOURCE_HASH_MISMATCH");
                String sourceId = row.path("id").asText();
                String filename = dataset + "-" + sourceId.substring(sourceId.indexOf(':') + 1, sourceId.indexOf(':') + 17) + ".txt";
                var stored = storage.store(new ByteArrayInputStream(bytes), "txt", bytes.length);
                var textChunks = splitter.split(new ParsedDocument(text, List.of(), Map.of(), List.of(), List.of()), chunking.toOptions());
                var document = transaction.execute(status -> {
                    var saved = docRepository.saveAndFlush(Document.uploaded(kbId, filename, stored.storageKey(), "text/plain", "txt", bytes.length, sha(text)));
                    saved.markProcessing(); docRepository.saveAndFlush(saved); return saved;
                });
                var persisted = transaction.execute(status -> chunkRepository.saveAllAndFlush(textChunks.stream()
                        .map(chunk -> DocumentChunk.create(kbId, document.getId(), chunk)).toList()));
                corpusChunks.addAll(persisted); ownedDocuments.add(document);
                var item = new LinkedHashMap<String, Object>();
                item.put("sourceId", sourceId); item.put("dataset", dataset); item.put("knowledgeBaseId", kbId);
                item.put("documentId", document.getId()); item.put("title", row.path("title").asText());
                item.put("sourceTextSha256", row.path("textSha256").asText());
                item.put("chunks", persisted.stream().map(chunk -> Map.of("chunkId", chunk.getId(), "chunkIndex", chunk.getChunkIndex(),
                        "content", chunk.getContent(), "contentHash", chunk.getContentHash())).toList());
                documents.add(item);
                journal.write(mapper.writeValueAsString(item)); journal.newLine(); journal.flush();
                if (documents.size() % 100 == 0) System.out.println("PUBLIC_SEED_PERSISTED=" + documents.size());
            }
        }
        var titleByDocument = new HashMap<Long, String>();
        documents.forEach(doc -> titleByDocument.put(((Number) doc.get("documentId")).longValue(), String.valueOf(doc.get("title"))));
        int batchSize = Math.min(10, vectorProperties.getEmbedding().getBatchSize());
        for (int start = 0; start < corpusChunks.size(); start += batchSize) {
            var batch = corpusChunks.subList(start, Math.min(start + batchSize, corpusChunks.size()));
            vector.upsert(batch.stream().map(chunk -> new ChunkVector(chunk.getId(), chunk.getDocumentId(), chunk.getKnowledgeBaseId(),
                    chunk.getChunkIndex(), chunk.getPageFrom(), chunk.getPageTo(), chunk.getContentHash(), vectorProperties.getEmbedding().getModel(), chunk.getContent())).toList());
            keyword.upsert(batch.stream().map(chunk -> new KeywordDocument(chunk.getId(), chunk.getKnowledgeBaseId(), chunk.getDocumentId(),
                    chunk.getContent(), titleByDocument.get(chunk.getDocumentId()), chunk.getSectionTitle(), chunk.getPageFrom(), chunk.getPageTo(), chunk.getContentHash())).toList());
            if (start % 100 == 0) System.out.println("PUBLIC_SEED_INDEXED=" + Math.min(start + batchSize, corpusChunks.size()) + "/" + corpusChunks.size());
        }
        context.getBean(ElasticsearchClient.class).indices().refresh(request -> request.index(property("app.keyword.alias", "")));
        transaction.executeWithoutResult(status -> { ownedDocuments.forEach(Document::markReady); docRepository.saveAllAndFlush(ownedDocuments); });
        var binding = new LinkedHashMap<String, Object>();
        binding.put("schemaVersion", "public-binding-v1"); binding.put("runId", runId); binding.put("manifestSha256", manifestSha);
        binding.put("knowledgeBases", kbIds); binding.put("documents", documents); binding.put("chunkCount", corpusChunks.size());
        binding.put("chunking", Map.of("strategy", chunking.getStrategy(), "unit", chunking.getWindowUnit().name(), "size", chunking.getChunkSize(), "overlap", chunking.getOverlap()));
        binding.put("scope", "Production chunker and real index ports; explicit corpus seeding excludes upload/Outbox/OCR performance");
        binding.put("vectorCollection", property("app.vector.qdrant.collection-name", "")); binding.put("keywordAlias", property("app.keyword.alias", ""));
        write(bindingPath, binding);
        System.out.println("PUBLIC_BINDING=" + bindingPath + "; documents=" + documents.size() + "; chunks=" + corpusChunks.size());
    }

    private JsonNode binding() throws Exception {
        var binding = mapper.readTree(Files.readString(prepared.resolve("binding.json"), StandardCharsets.UTF_8));
        if (!manifestSha.equals(binding.path("manifestSha256").asText())
                || !property("app.vector.qdrant.collection-name", "").equals(binding.path("vectorCollection").asText())
                || !property("app.keyword.alias", "").equals(binding.path("keywordAlias").asText())) throw new IllegalStateException("BINDING_CONFIGURATION_MISMATCH");
        var repository = context.getBean(DocumentChunkRepository.class);
        for (String dataset : List.of("cmrc2018", "hotpotqa")) {
            long kbId = binding.path("knowledgeBases").path(dataset).asLong();
            var actual = repository.findByKnowledgeBaseIdOrderByIdAsc(kbId);
            var expected = new HashMap<Long, String>();
            for (var doc : binding.path("documents")) if (doc.path("dataset").asText().equals(dataset)) {
                for (var chunk : doc.path("chunks")) expected.put(chunk.path("chunkId").asLong(), chunk.path("contentHash").asText());
            }
            if (actual.size() != expected.size() || actual.stream().anyMatch(chunk -> !chunk.getContentHash().equals(expected.get(chunk.getId())))) throw new IllegalStateException("PERSISTED_CORPUS_CHANGED");
        }
        return binding;
    }

    private Map<String, Object> observation(JsonNode sample, JsonNode binding, String phase, String mode, int ordinal) {
        long started = System.nanoTime();
        long kbId = binding.path("knowledgeBases").path(sample.path("dataset").asText()).asLong();
        var row = new LinkedHashMap<String, Object>();
        row.put("caseId", sample.path("id").asText()); row.put("dataset", sample.path("dataset").asText());
        row.put("phase", phase); row.put("mode", mode); row.put("ordinal", ordinal); row.put("knowledgeBaseId", kbId);
        row.put("startedAt", Instant.now().toString());
        try {
            String question = sample.path("question").asText();
            if (phase.equals("retrieval") || phase.equals("warmup") || phase.equals("latency")) {
                var result = context.getBean(HybridRetriever.class).retrieve(new RetrievalQuery(kbId, question, 40, 40, 5), rerank);
                row.put("top5", result.chunks()); row.put("degraded", result.degraded()); row.put("unavailableChannels", result.unavailableChannels());
                row.put("rerankingApplied", result.rerankingApplied()); row.put("rerankingDegraded", result.rerankingDegraded());
                if (result.degraded() || result.rerankingDegraded() || (rerank && !result.rerankingApplied())) row.put("errorCode", "RETRIEVAL_DEGRADED");
            } else {
                String suffix = sample.path("dataset").asText().equals("cmrc2018")
                        ? "\n请只给出简短答案实体或短语，并保留支持答案的引用。" : "\nGive only the short answer entity or phrase, with supporting citations.";
                row.put("questionSuffix", suffix);
                if (phase.equals("qa")) {
                    var result = context.getBean(RagQueryApplicationService.class).queryForConversation(kbId, new RagQueryCommand(question + suffix, 8, rerank), List.of());
                    row.put("answer", result.response().answer()); row.put("citations", result.response().citations());
                    row.put("evidence", result.evidence()); row.put("retrieval", result.response().retrieval());
                    row.put("retrievalTrace", result.retrievalTrace()); row.put("promptSha256", result.promptSha256());
                    if (result.completion() != null) row.put("model", result.completion().modelName());
                    if (result.response().retrieval().degraded()) row.put("errorCode", "RETRIEVAL_DEGRADED");
                } else {
                    var request = new AgentRequest(question + suffix, kbId, null, AgentMode.valueOf(mode), Map.of());
                    var execution = AgentExecutionContext.forRequest(request, UUID.randomUUID().toString(), Instant.now().plusSeconds(120),
                            new AgentConversationContext(kbId, null, List.of()), event -> { }, new AgentCancellationToken());
                    var result = context.getBean(AgentRouter.class).execute(request, execution);
                    row.put("answer", result.answer() == null ? "" : result.answer()); row.put("citations", result.citations());
                    row.put("status", result.status()); row.put("usage", result.usage()); row.put("model", result.modelName() == null ? "" : result.modelName());
                    row.put("steps", result.steps());
                    if (result.failure() != null) row.put("errorCode", result.failure().code().name());
                }
            }
        } catch (RuntimeException failure) {
            row.put("errorCode", failure instanceof BusinessException business ? business.getErrorCode().code()
                    : failure instanceof VectorIndexException vector ? vector.getErrorCode().name() : failure.getClass().getSimpleName());
        }
        row.put("durationNanos", System.nanoTime() - started);
        row.putIfAbsent("errorCode", "");
        return row;
    }

    private void measure(String phase) throws Exception {
        var binding = binding();
        var samples = selected("qa.jsonl");
        if (phase.equals("agent")) samples = samples.stream().filter(sample -> sample.path("dataset").asText().equals("hotpotqa")).toList();
        var runId = stamp();
        int concurrency = Integer.parseInt(property("public.concurrency", phase.equals("latency") ? "10" : "2"));
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", "public-observations-v1"); report.put("runId", runId); report.put("phase", phase);
        report.put("manifestSha256", manifestSha); report.put("bindingSha256", sha(Files.readString(prepared.resolve("binding.json"), StandardCharsets.UTF_8)));
        report.put("rerank", rerank); report.put("concurrency", concurrency); report.put("limitPerDataset", limit);
        report.put("createdAt", Instant.now().toString()); report.put("labelPolicy", "original public accepted answers and evidence; no human-final business accuracy claim");
        report.put("runtime", Map.of("javaVersion", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "osVersion", System.getProperty("os.version"), "jvmActiveProcessors", Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory(), "applicationClassesSha256", property("public.application-classes-sha256", "unavailable"),
                "adapterSourceSha256", property("public.adapter-source-sha256", "unavailable")));
        report.put("configurationSchemaVersion", "public-configuration-v2");
        report.put("configuration", Map.of("chatModel", property("spring.ai.openai.chat.model", ""),
                "embeddingModel", property("app.vector.embedding.model", ""), "rerankModel", property("app.rerank.model", ""),
                "vectorCollection", property("app.vector.qdrant.collection-name", ""), "keywordAlias", property("app.keyword.alias", ""),
                "embeddingTransport", "JDK HTTP/1.1 BenchmarkHttpEmbeddingModel; no cache",
                "queryInstruction", phase.equals("qa") || phase.equals("agent") ? "short answer entity/phrase with citations" : "unaltered original question",
                "vectorTopK", 40, "keywordTopK", 40,
                "retrievalTopK", phase.equals("agent") ? "mode/tool-specific; see recorded steps and citations" : phase.equals("qa") ? 8 : 5));
        var observations = new ArrayList<Map<String, Object>>();
        var jobs = new ArrayList<Callable<Map<String, Object>>>();
        var consecutiveErrors = new AtomicInteger();
        try (BufferedWriter journal = Files.newBufferedWriter(reports.resolve(phase + "-" + runId + ".jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            if (phase.equals("latency")) {
                int warmup = Integer.parseInt(property("public.warmup", "50"));
                int measured = Integer.parseInt(property("public.samples", "500"));
                if (warmup < 50 || measured < 500) throw new IllegalArgumentException("Formal latency requires >=50 warmups and >=500 requests");
                report.put("warmup", warmup); report.put("samples", measured);
                var random = new Random(20261019L);
                boolean invalidWarmup = false;
                for (int i = 0; i < warmup; i++) {
                    var observation = observation(samples.get(random.nextInt(samples.size())), binding, "warmup", "", i);
                    observations.add(observation); journal.write(mapper.writeValueAsString(observation)); journal.newLine(); journal.flush();
                    if (!String.valueOf(observation.get("errorCode")).isEmpty()) { invalidWarmup = true; break; }
                }
                report.put("invalidWarmup", invalidWarmup);
                if (!invalidWarmup) for (int i = 0; i < measured; i++) {
                    var sample = samples.get(random.nextInt(samples.size())); int ordinal = i;
                    jobs.add(() -> observation(sample, binding, phase, "", ordinal));
                }
            } else {
                int ordinal = 0;
                for (var sample : samples) for (String mode : phase.equals("agent") ? List.of("RETRIEVAL_FIRST", "REACT", "PLAN_EXECUTE_REFLECT") : List.of("")) {
                    int current = ordinal++;
                    jobs.add(() -> {
                        if (consecutiveErrors.get() >= 3) return new LinkedHashMap<>(Map.of("caseId", sample.path("id").asText(),
                                "dataset", sample.path("dataset").asText(), "phase", phase, "mode", mode, "ordinal", current,
                                "errorCode", "NOT_RUN_AFTER_DEPENDENCY_ERRORS"));
                        var observation = observation(sample, binding, phase, mode, current);
                        String error = String.valueOf(observation.get("errorCode"));
                        boolean dependencyError = error.equals("RETRIEVAL_DEGRADED") || error.startsWith("EMBEDDING_")
                                || error.contains("MODEL_UNAVAILABLE") || error.contains("MODEL_TIMEOUT")
                                || error.contains("HYBRID_RETRIEVAL_UNAVAILABLE") || error.contains("CONNECTION");
                        if (dependencyError) consecutiveErrors.incrementAndGet(); else consecutiveErrors.set(0);
                        return observation;
                    });
                }
            }
            try (var executor = Executors.newFixedThreadPool(concurrency)) {
                var futures = jobs.stream().map(executor::submit).toList();
                int completed = 0;
                for (var future : futures) {
                    var observation = future.get(); observations.add(observation);
                    journal.write(mapper.writeValueAsString(observation)); journal.newLine(); journal.flush();
                    if (++completed % 10 == 0) System.out.println("PUBLIC_" + phase.toUpperCase() + "=" + completed + "/" + jobs.size());
                }
            }
        }
        report.put("expectedJobs", jobs.size()); report.put("observations", observations);
        report.put("corpusStable", binding().equals(binding));
        Path path = reports.resolve(phase + "-" + runId + ".json"); write(path, report);
        System.out.println("PUBLIC_REPORT=" + path);
    }

    public static void main(String[] args) throws Exception {
        var application = new SpringApplication(AiDocumentAgentApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(raw -> {
            var context = (GenericApplicationContext) raw;
            context.registerBean("publicBenchmarkVectorIndex", VectorIndex.class, () -> {
                var properties = context.getBean(VectorProperties.class);
                var translator = context.getBean(VectorIndexExceptionTranslator.class);
                var environment = context.getEnvironment();
                var embedding = new BenchmarkHttpEmbeddingModel(java.net.URI.create(environment.getRequiredProperty("spring.ai.openai.embedding.base-url")),
                        environment.getRequiredProperty("spring.ai.openai.embedding.api-key"), properties.getEmbedding().getModel(),
                        properties.getEmbedding().getDimensions(), properties.getEmbedding().getTimeout(), context.getBean(ObjectMapper.class));
                var store = QdrantVectorStore.builder(context.getBean(QdrantClient.class), new ClassifyingEmbeddingModel(embedding, properties, translator))
                        .collectionName(properties.getQdrant().getCollectionName()).initializeSchema(false).build();
                return new QdrantVectorIndex(store, properties, translator);
            }, definition -> definition.setPrimary(true));
        });
        var arguments = new ArrayList<>(List.of(args));
        var forced = Map.of("spring.flyway.enabled", "false", "app.ingestion.enabled", "false", "app.progress.enabled", "false",
                "app.agent.execution.enabled", "false", "app.benchmark.retrieval.enabled", "false", "app.benchmark.rag.enabled", "false",
                "app.benchmark.agent.enabled", "false", "spring.rabbitmq.listener.simple.auto-startup", "false");
        arguments.removeIf(argument -> forced.keySet().stream().anyMatch(key -> argument.startsWith("--" + key + "=")));
        forced.forEach((key, value) -> arguments.add("--" + key + "=" + value));
        try (var context = application.run(arguments.toArray(String[]::new))) {
            var bridge = new PublicBenchmarkBridge(context);
            String phase = bridge.property("public.phase", "retrieval");
            if (phase.equals("seed")) bridge.seed(); else bridge.measure(phase);
        }
    }
}
