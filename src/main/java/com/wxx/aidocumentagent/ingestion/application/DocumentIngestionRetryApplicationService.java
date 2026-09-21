package com.wxx.aidocumentagent.ingestion.application;

import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.api.dto.IngestionJobResponse;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FAILED 文档的人工恢复入口：已有 batch 时仅重投未完成 batch，不重复切分。 */
@Service
public class DocumentIngestionRetryApplicationService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentBatchTaskRepository batchTaskRepository;
    private final DocumentIngestionOutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentIngestionRetryApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                                     DocumentRepository documentRepository,
                                                     DocumentIngestionJobRepository jobRepository,
                                                     DocumentBatchTaskRepository batchTaskRepository,
                                                     DocumentIngestionOutboxEventRepository outboxEventRepository,
                                                     ApplicationEventPublisher eventPublisher) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.batchTaskRepository = batchTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public IngestionJobResponse retry(long knowledgeBaseId, long documentId) {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        Document document = documentRepository.findByIdAndKnowledgeBaseId(documentId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(com.wxx.aidocumentagent.document.domain.DocumentErrorCode.DOCUMENT_NOT_FOUND));
        DocumentIngestionJob job = jobRepository.findLockedByDocumentIdAndKnowledgeBaseId(documentId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(IngestionErrorCode.INGESTION_JOB_NOT_FOUND));
        if (job.getStatus() != com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus.FAILED) {
            throw new BusinessException(IngestionErrorCode.INGESTION_RETRY_NOT_ALLOWED);
        }

        List<DocumentBatchTask> tasks = batchTaskRepository.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(job.getJobId(),
                knowledgeBaseId);
        job.prepareManualRetry();
        document.markRetrying(null, null);
        if (tasks.isEmpty()) {
            publishAfterCommit(outboxEventRepository.save(
                    DocumentIngestionOutboxEvent.document(job, 0, OutboxDispatchMode.PRIMARY, 0)));
        }
        else {
            List<DocumentBatchTask> retryTasks = tasks.stream()
                    .filter(task -> task.getStatus() != BatchTaskStatus.COMPLETED)
                    .toList();
            if (retryTasks.isEmpty()) {
                throw new BusinessException(IngestionErrorCode.INGESTION_RETRY_NOT_ALLOWED);
            }
            retryTasks.forEach(DocumentBatchTask::prepareManualRetry);
            outboxEventRepository.saveAll(retryTasks.stream()
                    .map(task -> DocumentIngestionOutboxEvent.batch(job, task, 0, OutboxDispatchMode.PRIMARY, 0))
                    .toList()).forEach(this::publishAfterCommit);
        }
        return toResponse(job);
    }

    private IngestionJobResponse toResponse(DocumentIngestionJob job) {
        return new IngestionJobResponse(job.getJobId(), job.getDocumentId(), job.getKnowledgeBaseId(), job.getStatus(),
                job.getAttempt(), job.getTotalBatchCount(), job.getCompletedBatchCount(), job.getFailedBatchCount(),
                job.getErrorCode(), job.getErrorMessage(), job.getEnqueuedAt(), job.getStartedAt(), job.getCompletedAt());
    }

    private void publishAfterCommit(DocumentIngestionOutboxEvent event) {
        eventPublisher.publishEvent(new IngestionOutboxCreatedEvent(event.getEventId()));
    }
}
