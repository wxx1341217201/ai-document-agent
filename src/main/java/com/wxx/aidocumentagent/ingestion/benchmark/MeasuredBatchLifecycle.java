package com.wxx.aidocumentagent.ingestion.benchmark;

import java.util.Optional;

import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.application.BatchWork;
import com.wxx.aidocumentagent.ingestion.application.ChunkBatchTaskLifecycleService;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.*;
import com.wxx.aidocumentagent.ingestion.messaging.ChunkBatchMessage;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Uses the production state machine, with observations emitted only after successful commit. */
public class MeasuredBatchLifecycle extends ChunkBatchTaskLifecycleService {
    private final DocumentBatchTaskRepository batches;
    private final DocumentIngestionJobRepository jobs;
    private final RabbitBenchmarkMetrics metrics;

    public MeasuredBatchLifecycle(DocumentBatchTaskRepository batches, DocumentIngestionJobRepository jobs,
                                  DocumentRepository documents, DocumentIngestionOutboxEventRepository outbox,
                                  RabbitBenchmarkMetrics metrics) {
        super(batches, jobs, documents, outbox);
        this.batches = batches;
        this.jobs = jobs;
        this.metrics = metrics;
    }

    @Override @Transactional
    public Optional<BatchWork> claim(ChunkBatchMessage message) {
        Optional<BatchWork> work = super.claim(message);
        work.ifPresent(w -> {
            DocumentBatchTask task = batches.findByBatchIdAndKnowledgeBaseId(w.batchId(), w.knowledgeBaseId()).orElseThrow();
            var enqueued = task.getEnqueuedAt();
            var started = task.getStartedAt();
            afterCommit(() -> metrics.started(w.batchId(), w.stage().name(), w.documentId(),
                    w.chunkTo() - w.chunkFrom() + 1, enqueued, started));
        });
        return work;
    }

    @Override @Transactional
    public void complete(BatchWork work) {
        super.complete(work);
        DocumentIngestionJob job = jobs.findByJobIdAndKnowledgeBaseId(work.jobId(), work.knowledgeBaseId()).orElseThrow();
        boolean ready = job.getStatus() == IngestionJobStatus.READY;
        afterCommit(() -> {
            double committed = RabbitBenchmarkMetrics.now();
            metrics.completed(work.batchId(), work.stage().name(), committed);
            if (ready && work.stage() == ChunkBatchStage.KEYWORD_INDEX) metrics.ready(work.documentId(), committed);
        });
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
