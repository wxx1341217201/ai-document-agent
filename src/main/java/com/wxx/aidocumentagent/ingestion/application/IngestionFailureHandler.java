package com.wxx.aidocumentagent.ingestion.application;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.ingestion.messaging.ChunkBatchMessage;
import com.wxx.aidocumentagent.ingestion.messaging.DocumentIngestionMessage;
import com.wxx.aidocumentagent.ingestion.messaging.IngestionMessageValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 失败处理将消息确认策略与持久化状态机解耦，避免依赖 RabbitMQ 的恰好一次投递。 */
@Service
public class IngestionFailureHandler {

    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentBatchTaskRepository batchTaskRepository;
    private final DocumentIngestionOutboxEventRepository outboxEventRepository;
    private final DocumentRepository documentRepository;
    private final IngestionFailureClassifier failureClassifier;
    private final IngestionProperties properties;

    public IngestionFailureHandler(DocumentIngestionJobRepository jobRepository,
                                   DocumentBatchTaskRepository batchTaskRepository,
                                   DocumentIngestionOutboxEventRepository outboxEventRepository,
                                   DocumentRepository documentRepository,
                                   IngestionFailureClassifier failureClassifier,
                                   IngestionProperties properties) {
        this.jobRepository = jobRepository;
        this.batchTaskRepository = batchTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.documentRepository = documentRepository;
        this.failureClassifier = failureClassifier;
        this.properties = properties;
    }

    @Transactional
    public IngestionFailureDisposition handleDocumentFailure(DocumentIngestionMessage message, Throwable failure) {
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(message.jobId().toString(),
                message.knowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("摄取任务不存在"));
        validateDocumentMessage(job, message);
        Document document = documentRepository.findByIdAndKnowledgeBaseId(message.documentId(), message.knowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("摄取文档不存在或知识库不匹配"));
        if (job.getStatus().isTerminal()) {
            return IngestionFailureDisposition.IGNORE;
        }
        IngestionFailureSummary summary = failureClassifier.classify(failure);
        int nextAttempt = Math.max(job.getAttempt(), message.attempt()) + 1;
        if (summary.retryable() && nextAttempt < properties.getMaxAttempts()) {
            job.markRetrying(nextAttempt, summary.code(), summary.message());
            document.markRetrying(summary.code(), summary.message());
            outboxEventRepository.save(DocumentIngestionOutboxEvent.document(job, nextAttempt, OutboxDispatchMode.RETRY,
                    properties.retryDelayMillis(nextAttempt)));
            return IngestionFailureDisposition.RETRY_SCHEDULED;
        }
        job.markFailed(summary.code(), summary.message(), LocalDateTime.now());
        document.markFailed(summary.code(), summary.message());
        return IngestionFailureDisposition.DEAD_LETTER;
    }

    @Transactional
    public IngestionFailureDisposition handleBatchFailure(ChunkBatchMessage message, Throwable failure) {
        DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(message.batchId().toString(),
                message.knowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("batch任务不存在"));
        validateBatchMessage(task, message);
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(task.getJobId(), task.getKnowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch所属任务不存在"));
        Document document = documentRepository.findByIdAndKnowledgeBaseId(task.getDocumentId(), task.getKnowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch所属文档不存在或知识库不匹配"));
        if (task.getStatus().isTerminal() || job.getStatus().isTerminal()) {
            return IngestionFailureDisposition.IGNORE;
        }
        IngestionFailureSummary summary = failureClassifier.classify(failure);
        int nextAttempt = Math.max(task.getAttempt(), message.attempt()) + 1;
        if (summary.retryable() && nextAttempt < properties.getMaxAttempts()) {
            task.markRetrying(nextAttempt, summary.code(), summary.message());
            job.markRetrying(Math.max(job.getAttempt(), nextAttempt), summary.code(), summary.message());
            document.markRetrying(summary.code(), summary.message());
            outboxEventRepository.save(DocumentIngestionOutboxEvent.batch(job, task, nextAttempt,
                    OutboxDispatchMode.RETRY, properties.retryDelayMillis(nextAttempt)));
            return IngestionFailureDisposition.RETRY_SCHEDULED;
        }
        task.markFailed(summary.code(), summary.message(), LocalDateTime.now());
        batchTaskRepository.flush();
        int completed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(),
                job.getKnowledgeBaseId(), BatchTaskStatus.COMPLETED));
        int failed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(),
                job.getKnowledgeBaseId(), BatchTaskStatus.FAILED));
        job.updateBatchSummary(job.getTotalBatchCount(), completed, failed);
        job.markFailed(summary.code(), summary.message(), LocalDateTime.now());
        document.markFailed(summary.code(), summary.message());
        return IngestionFailureDisposition.DEAD_LETTER;
    }

    private void validateDocumentMessage(DocumentIngestionJob job, DocumentIngestionMessage message) {
        if (job.getDocumentId() != message.documentId() || job.getKnowledgeBaseId() != message.knowledgeBaseId()
                || job.getOperation() != message.operation()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }

    private void validateBatchMessage(DocumentBatchTask task, ChunkBatchMessage message) {
        if (!task.getJobId().equals(message.jobId().toString()) || task.getDocumentId() != message.documentId()
                || task.getKnowledgeBaseId() != message.knowledgeBaseId() || task.getChunkFrom() != message.chunkFrom()
                || task.getChunkTo() != message.chunkTo() || task.getStage() != message.stage()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }
}
