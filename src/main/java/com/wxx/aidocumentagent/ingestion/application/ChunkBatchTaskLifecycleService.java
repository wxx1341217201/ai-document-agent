package com.wxx.aidocumentagent.ingestion.application;

import java.time.LocalDateTime;
import java.util.Optional;

import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.messaging.ChunkBatchMessage;
import com.wxx.aidocumentagent.ingestion.messaging.IngestionMessageValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** batch 领取与聚合完成均使用数据库行锁，允许多个 RabbitMQ worker 安全并行。 */
@Service
public class ChunkBatchTaskLifecycleService {

    private final DocumentBatchTaskRepository batchTaskRepository;
    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentRepository documentRepository;

    public ChunkBatchTaskLifecycleService(DocumentBatchTaskRepository batchTaskRepository,
                                          DocumentIngestionJobRepository jobRepository,
                                          DocumentRepository documentRepository) {
        this.batchTaskRepository = batchTaskRepository;
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public Optional<BatchWork> claim(ChunkBatchMessage message) {
        DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(message.batchId().toString(),
                message.knowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("batch任务不存在"));
        validateTaskScope(task, message);
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(task.getJobId(),
                task.getKnowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("batch所属任务不存在"));
        validateJobScope(job, task);
        Document document = documentRepository.findByIdAndKnowledgeBaseId(task.getDocumentId(), task.getKnowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch所属文档不存在或知识库不匹配"));

        if (task.getStatus().isTerminal() || job.getStatus().isTerminal()) {
            return Optional.empty();
        }
        if (task.getStatus() == BatchTaskStatus.PENDING_DISPATCH) {
            throw new RetryableIngestionException("batch尚未收到publisher confirm");
        }
        if (!task.beginProcessing(LocalDateTime.now())) {
            // PROCESSING 表示已被另一个消费者领取；崩溃恢复由持久化超时扫描处理。
            return Optional.empty();
        }
        job.beginProcessing(LocalDateTime.now());
        document.markProcessing();
        return Optional.of(new BatchWork(task.getBatchId(), task.getJobId(), task.getKnowledgeBaseId(),
                task.getDocumentId(), task.getChunkFrom(), task.getChunkTo()));
    }

    @Transactional
    public void complete(BatchWork work) {
        DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(work.batchId(),
                work.knowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("batch任务不存在"));
        if (task.getStatus() == BatchTaskStatus.COMPLETED || task.getStatus() == BatchTaskStatus.FAILED) {
            return;
        }
        if (task.getStatus() != BatchTaskStatus.PROCESSING) {
            throw new IllegalStateException("batch未被合法领取，不能完成");
        }
        task.markCompleted(LocalDateTime.now());
        batchTaskRepository.flush();

        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(work.jobId(), work.knowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch所属任务不存在"));
        Document document = documentRepository.findByIdAndKnowledgeBaseId(work.documentId(), work.knowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch所属文档不存在或知识库不匹配"));
        int completed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(),
                job.getKnowledgeBaseId(), BatchTaskStatus.COMPLETED));
        int failed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(),
                job.getKnowledgeBaseId(), BatchTaskStatus.FAILED));
        job.updateBatchSummary(job.getTotalBatchCount(), completed, failed);
        if (job.getStatus().isTerminal()) {
            return;
        }
        if (failed > 0) {
            job.markFailed(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage(), LocalDateTime.now());
            document.markFailed(IngestionErrorCode.INGESTION_PROCESSING_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PROCESSING_FAILURE.defaultMessage());
            return;
        }
        if (completed == job.getTotalBatchCount()) {
            job.markReady(LocalDateTime.now());
            document.markReady();
        }
    }

    private void validateTaskScope(DocumentBatchTask task, ChunkBatchMessage message) {
        if (!task.getJobId().equals(message.jobId().toString()) || task.getDocumentId() != message.documentId()
                || task.getKnowledgeBaseId() != message.knowledgeBaseId() || task.getChunkFrom() != message.chunkFrom()
                || task.getChunkTo() != message.chunkTo() || task.getStage() != message.stage()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }

    private void validateJobScope(DocumentIngestionJob job, DocumentBatchTask task) {
        if (job.getDocumentId() != task.getDocumentId() || job.getKnowledgeBaseId() != task.getKnowledgeBaseId()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }
}
