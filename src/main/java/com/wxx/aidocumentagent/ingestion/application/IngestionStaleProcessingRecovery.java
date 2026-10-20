package com.wxx.aidocumentagent.ingestion.application;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.progress.ProgressEmitter;
import com.wxx.aidocumentagent.progress.ProgressEventType;
import com.wxx.aidocumentagent.progress.ProgressPayload;
import com.wxx.aidocumentagent.progress.ProgressStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 进程在业务完成、ACK 之前中断时，通过持久化时间戳恢复卡住的工作。 */
@Service
public class IngestionStaleProcessingRecovery {

    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentBatchTaskRepository batchTaskRepository;
    private final DocumentIngestionOutboxEventRepository outboxEventRepository;
    private final DocumentRepository documentRepository;
    private final IngestionProperties properties;
    private ProgressEmitter progress;

    public IngestionStaleProcessingRecovery(DocumentIngestionJobRepository jobRepository,
                                            DocumentBatchTaskRepository batchTaskRepository,
                                            DocumentIngestionOutboxEventRepository outboxEventRepository,
                                            DocumentRepository documentRepository,
                                            IngestionProperties properties) {
        this.jobRepository = jobRepository;
        this.batchTaskRepository = batchTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.documentRepository = documentRepository;
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setProgressEmitter(ProgressEmitter progress) { this.progress = progress; }

    @Transactional
    public void recoverJob(String jobId, long knowledgeBaseId) {
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(jobId, knowledgeBaseId).orElse(null);
        if (job == null || job.getStatus() != IngestionJobStatus.PROCESSING || job.getTotalBatchCount() > 0) {
            return;
        }
        Document document = documentRepository.findByIdAndKnowledgeBaseId(job.getDocumentId(), knowledgeBaseId).orElse(null);
        if (document == null) {
            return;
        }
        int nextAttempt = job.getAttempt() + 1;
        if (nextAttempt < properties.getMaxAttempts()) {
            job.markRetrying(nextAttempt, IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.code(),
                    IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.defaultMessage());
            document.markRetrying(IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.code(),
                    IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.defaultMessage());
            outboxEventRepository.save(DocumentIngestionOutboxEvent.document(job, nextAttempt, OutboxDispatchMode.RETRY,
                    properties.retryDelayMillis(nextAttempt)));
        }
        else {
            job.markFailed(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage(), LocalDateTime.now());
            document.markFailed(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage());
            failed(job);
        }
    }

    @Transactional
    public void recoverBatch(String batchId, long knowledgeBaseId) {
        DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(batchId, knowledgeBaseId)
                .orElse(null);
        if (task == null || task.getStatus() != BatchTaskStatus.PROCESSING) {
            return;
        }
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(task.getJobId(), knowledgeBaseId)
                .orElse(null);
        Document document = documentRepository.findByIdAndKnowledgeBaseId(task.getDocumentId(), knowledgeBaseId).orElse(null);
        if (job == null || document == null || job.getStatus().isTerminal()) {
            return;
        }
        int nextAttempt = task.getAttempt() + 1;
        if (nextAttempt < properties.getMaxAttempts()) {
            task.markRetrying(nextAttempt, IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.code(),
                    IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.defaultMessage());
            job.markRetrying(Math.max(job.getAttempt(), nextAttempt),
                    IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.code(),
                    IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.defaultMessage());
            document.markRetrying(IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.code(),
                    IngestionErrorCode.INGESTION_TRANSIENT_FAILURE.defaultMessage());
            outboxEventRepository.save(DocumentIngestionOutboxEvent.batch(job, task, nextAttempt,
                    OutboxDispatchMode.RETRY, properties.retryDelayMillis(nextAttempt)));
        }
        else {
            task.markFailed(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage(), LocalDateTime.now());
            batchTaskRepository.flush();
            int completed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(
                    job.getJobId(), knowledgeBaseId, BatchTaskStatus.COMPLETED));
            int failed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(
                    job.getJobId(), knowledgeBaseId, BatchTaskStatus.FAILED));
            job.updateBatchSummary(job.getTotalBatchCount(), completed, failed);
            job.markFailed(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage(), LocalDateTime.now());
            document.markFailed(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage());
            failed(job);
        }
    }

    private void failed(DocumentIngestionJob job) {
        if (progress != null) progress.afterCommit(
                ProgressStream.document(job.getKnowledgeBaseId(), job.getJobId(), job.getDocumentId()),
                ProgressEventType.DOCUMENT_FAILED,
                ProgressPayload.document(job.getDocumentId(), "FAILED", "文档处理失败",
                        IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code()));
    }
}
