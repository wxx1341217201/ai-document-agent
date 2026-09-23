package com.wxx.aidocumentagent.ingestion.application;

import java.time.LocalDateTime;
import java.util.Optional;

import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
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
    private final DocumentIngestionOutboxEventRepository outboxEventRepository;

    public ChunkBatchTaskLifecycleService(DocumentBatchTaskRepository batchTaskRepository,
                                          DocumentIngestionJobRepository jobRepository,
                                          DocumentRepository documentRepository,
                                          DocumentIngestionOutboxEventRepository outboxEventRepository) {
        this.batchTaskRepository = batchTaskRepository;
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public Optional<BatchWork> claim(ChunkBatchMessage message) {
        DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(message.batchId().toString(),
                message.knowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("batch任务不存在"));
        validateTaskIdentity(task, message);
        if (task.getStage() != message.normalizedStage()) {
            if (task.getStage().isAfter(message.normalizedStage())) {
                // 同一 RabbitMQ 消息在 vector 成功并切换到 keyword 后重投，必须无副作用地确认。
                return Optional.empty();
            }
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
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
                task.getDocumentId(), task.getChunkFrom(), task.getChunkTo(), task.getStage()));
    }

    @Transactional
    public void complete(BatchWork work) {
        DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(work.batchId(),
                work.knowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("batch任务不存在"));
        validateWorkScope(task, work);
        if (task.getStatus() == BatchTaskStatus.COMPLETED || task.getStatus() == BatchTaskStatus.FAILED) {
            return;
        }
        if (task.getStatus() != BatchTaskStatus.PROCESSING || task.getStage() != work.stage()) {
            // 过期 worker 不能覆盖已由重试/恢复流程推进的状态。
            return;
        }
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(work.jobId(), work.knowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch所属任务不存在"));
        Document document = documentRepository.findByIdAndKnowledgeBaseId(work.documentId(), work.knowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch所属文档不存在或知识库不匹配"));
        if (job.getStatus().isTerminal()) {
            return;
        }

        if (work.stage() == ChunkBatchStage.VECTOR_INDEX) {
            task.advanceToKeywordIndex();
            // 与 stage 状态更新同事务写入。ES 失败时只重试 KEYWORD_INDEX，不重复推进聚合。
            outboxEventRepository.save(DocumentIngestionOutboxEvent.batch(job, task, 0, OutboxDispatchMode.PRIMARY, 0));
            return;
        }
        if (work.stage() != ChunkBatchStage.KEYWORD_INDEX) {
            throw new IllegalStateException("不支持完成的batch阶段: " + work.stage());
        }
        task.markCompleted(LocalDateTime.now());
        batchTaskRepository.flush();

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

    private void validateTaskIdentity(DocumentBatchTask task, ChunkBatchMessage message) {
        if (!task.getJobId().equals(message.jobId().toString()) || task.getDocumentId() != message.documentId()
                || task.getKnowledgeBaseId() != message.knowledgeBaseId() || task.getChunkFrom() != message.chunkFrom()
                || task.getChunkTo() != message.chunkTo()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }

    private void validateWorkScope(DocumentBatchTask task, BatchWork work) {
        if (!task.getJobId().equals(work.jobId()) || task.getDocumentId() != work.documentId()
                || task.getKnowledgeBaseId() != work.knowledgeBaseId() || task.getChunkFrom() != work.chunkFrom()
                || task.getChunkTo() != work.chunkTo()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }

    private void validateJobScope(DocumentIngestionJob job, DocumentBatchTask task) {
        if (job.getDocumentId() != task.getDocumentId() || job.getKnowledgeBaseId() != task.getKnowledgeBaseId()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }
}
