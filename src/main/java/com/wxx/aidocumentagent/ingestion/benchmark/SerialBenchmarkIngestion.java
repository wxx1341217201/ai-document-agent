package com.wxx.aidocumentagent.ingestion.benchmark;

import com.wxx.aidocumentagent.ingestion.application.*;
import com.wxx.aidocumentagent.ingestion.domain.*;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.*;
import com.wxx.aidocumentagent.ingestion.messaging.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** A has one fair serial lane; HTTP arrival and lock waiting are included in submit blocking. */
public class SerialBenchmarkIngestion {
    private final DocumentIngestionJobRepository jobs;
    private final DocumentBatchTaskRepository batches;
    private final JdbcTemplate jdbc;
    private final IngestionOutboxLifecycleService outboxLifecycle;
    private final DocumentIngestionCoordinator coordinator;
    private final ChunkBatchIndexingApplicationService worker;

    public SerialBenchmarkIngestion(DocumentIngestionJobRepository jobs, DocumentBatchTaskRepository batches,
                                    JdbcTemplate jdbc, IngestionOutboxLifecycleService lifecycle,
                                    DocumentIngestionCoordinator coordinator, ChunkBatchIndexingApplicationService worker) {
        this.jobs = jobs;
        this.batches = batches;
        this.jdbc = jdbc;
        outboxLifecycle = lifecycle;
        this.coordinator = coordinator;
        this.worker = worker;
    }
    public void process(long documentId, long knowledgeBaseId) {
        var job = jobs.findByDocumentIdAndKnowledgeBaseId(documentId, knowledgeBaseId).orElseThrow();
        dispatch(job.getJobId(), null, null);
        var tasks = batches.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(job.getJobId(), knowledgeBaseId);
        for (var task : tasks) {
            for (var stage : new ChunkBatchStage[] {ChunkBatchStage.VECTOR_INDEX, ChunkBatchStage.KEYWORD_INDEX}) {
                dispatch(job.getJobId(), task.getBatchId(), stage);
            }
        }
        if (jobs.findByJobIdAndKnowledgeBaseId(job.getJobId(), knowledgeBaseId).orElseThrow().getStatus() != IngestionJobStatus.READY)
            throw new IllegalStateException("Serial document did not reach DOCUMENT_READY");
    }
    private void dispatch(String jobId, String batchId, ChunkBatchStage stage) {
        String eventId = jdbc.queryForObject("SELECT event_id FROM document_ingestion_outbox_event "
                + "WHERE job_id=? AND status='PENDING' AND "
                + (batchId == null ? "batch_id IS NULL" : "batch_id=? AND batch_stage=?") + " ORDER BY id LIMIT 1",
                String.class, batchId == null ? new Object[] {jobId} : new Object[] {jobId, batchId, stage.name()});
        var envelope = awaitDispatchable(eventId);
        // A is an inline dispatch: no broker, but identical durable state transitions and processors.
        outboxLifecycle.markPublished(eventId);
        if (batchId == null) coordinator.process((DocumentIngestionMessage) envelope.payload());
        else worker.process((ChunkBatchMessage) envelope.payload());
    }

    private OutboxDispatchEnvelope awaitDispatchable(String eventId) {
        // MySQL DATETIME(3) rounds sub-millisecond availableAt values upward. An immediate
        // inline claim can therefore briefly be empty; the asynchronous dispatcher already scans later.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
        while (true) {
            var claimed = outboxLifecycle.claim(eventId);
            if (claimed.isPresent()) return claimed.orElseThrow();
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Inline outbox did not become dispatchable");
            try { Thread.sleep(1); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Inline dispatch interrupted", interrupted);
            }
        }
    }
}
