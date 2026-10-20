package com.wxx.aidocumentagent.ingestion.benchmark;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.wxx.aidocumentagent.document.application.DocumentApplicationService;
import com.wxx.aidocumentagent.document.application.UploadDocumentCommand;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBase;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 基准节点专用的执行编排、轮次隔离和验收统计。 */
@Service
@ConditionalOnProperty(name = "app.benchmark.rabbitmq.node", havingValue = "true")
public class RabbitBenchmarkApplicationService {
    private final DocumentApplicationService documents;
    private final KnowledgeBaseRepository knowledgeBases;
    private final SerialBenchmarkIngestion serial;
    private final RabbitBenchmarkMetrics metrics;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean synchronous;
    private final Map<String, Long> roundScopes = new ConcurrentHashMap<>();
    private final ReentrantLock serialLane = new ReentrantLock(true);

    public RabbitBenchmarkApplicationService(DocumentApplicationService documents, KnowledgeBaseRepository knowledgeBases,
            SerialBenchmarkIngestion serial, RabbitBenchmarkMetrics metrics, JdbcTemplate jdbc, Environment env,
            ObjectMapper mapper) {
        this.documents = documents;
        this.knowledgeBases = knowledgeBases;
        this.serial = serial;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.mapper = mapper;
        synchronous = env.getRequiredProperty("app.benchmark.rabbitmq.mode").equals("sync");
    }

    public Map<String, Object> begin(String phase) {
        if (!"warmup".equals(phase) && !"measure".equals(phase)) {
            throw new IllegalArgumentException("Invalid phase");
        }
        if (metrics.documents().stream().anyMatch(d -> d.readyAt == null)) {
            throw new IllegalStateException("Undrained phase");
        }
        metrics.begin(phase);
        return metrics.counters();
    }

    public Submission submit(UploadDocumentCommand command,
            String corpusId, int round, double arrived) throws InterruptedException {
        if (round < 0 || round > 100000 || corpusId == null || !corpusId.matches("doc-[0-9]{3}")) {
            throw new IllegalArgumentException("Invalid corpus identity");
        }
        if (synchronous) {
            serialLane.lockInterruptibly();
        }
        try {
            long knowledgeBaseId = roundScopes.computeIfAbsent(metrics.phase() + "/" + round,
                    key -> knowledgeBases.saveAndFlush(KnowledgeBase.create("m19e-" + key,
                            "Isolated benchmark corpus pass")).getId());
            var response = documents.upload(knowledgeBaseId, command);
            metrics.submitted(response.id(), corpusId, round, command.sizeBytes(), arrived);
            if (synchronous) {
                serial.process(response.id(), knowledgeBaseId);
            }
            return new Submission(response.id(), knowledgeBaseId);
        }
        finally {
            if (synchronous) {
                serialLane.unlock();
            }
        }
    }

    public Map<String, Object> status(long id) {
        var status = jdbc.queryForObject("SELECT status FROM document WHERE id=?", String.class, id);
        return Map.of("ready", metrics.isReady(id), "status", status);
    }

    public Map<String, Object> sample() {
        var sample = new LinkedHashMap<String, Object>(metrics.counters());
        sample.put("at", RabbitBenchmarkMetrics.now());
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        sample.put("processCpuTimeNanos", os.getProcessCpuTime());
        sample.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        sample.put("nonHeapUsedBytes", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        sample.put("documentBacklog", jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE status NOT IN ('READY','FAILED')", Long.class));
        sample.put("batchBacklog", jdbc.queryForObject("SELECT COUNT(*) FROM document_batch_task WHERE status NOT IN ('COMPLETED','FAILED')", Long.class));
        sample.put("failedDocuments", jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE status='FAILED'", Long.class));
        sample.put("retryAttempts", jdbc.queryForObject("SELECT COUNT(*) FROM document_ingestion_outbox_event WHERE message_attempt>0 AND dispatch_mode='RETRY'", Long.class));
        sample.put("publishRetryAttempts", jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(dispatch_attempt-1,0)),0) FROM document_ingestion_outbox_event", Long.class));
        return sample;
    }

    public Map<String, Object> export() {
        var verification = new LinkedHashMap<String, Object>();
        verification.put("persistedChunks", jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk", Long.class));
        verification.put("indexedChunks", jdbc.queryForObject("SELECT COUNT(*) FROM m19e_chunk_index", Long.class));
        verification.put("incompleteIndexes", jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk c LEFT JOIN m19e_chunk_index i "
                + "ON i.chunk_id=c.id WHERE i.vector_json IS NULL OR i.keyword_digest IS NULL", Long.class));
        verification.put("failedDocuments", jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE status='FAILED'", Long.class));
        verification.put("pendingDocuments", jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE status NOT IN ('READY','FAILED')", Long.class));
        verification.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        verification.put("transactionIsolation", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        var mismatches = new java.util.concurrent.atomic.AtomicLong();
        jdbc.query("SELECT c.content,i.vector_json,i.keyword_digest FROM document_chunk c LEFT JOIN m19e_chunk_index i ON i.chunk_id=c.id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    if (!mapper.writeValueAsString(LocalBenchmarkIndex.embedding(row.getString(1))).equals(row.getString(2))
                            || !LocalBenchmarkIndex.digest(row.getString(1)).equals(row.getString(3))) mismatches.incrementAndGet();
                });
        verification.put("mismatchedIndexes", mismatches.get());
        var actualChunks = new LinkedHashMap<Long, Long>();
        jdbc.query("SELECT document_id,COUNT(*) FROM document_chunk GROUP BY document_id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> actualChunks.put(row.getLong(1), row.getLong(2)));
        return Map.of("documents", metrics.documents(), "stages", metrics.stages(), "verification", verification,
                "actualChunksPerDocument", actualChunks, "sample", sample());
    }

    public record Submission(long documentId, long knowledgeBaseId) { }
}
