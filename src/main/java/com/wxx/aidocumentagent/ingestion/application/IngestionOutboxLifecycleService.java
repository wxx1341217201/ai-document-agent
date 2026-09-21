package com.wxx.aidocumentagent.ingestion.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxEventStatus;
import com.wxx.aidocumentagent.ingestion.domain.OutboxMessageType;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.ingestion.messaging.ChunkBatchMessage;
import com.wxx.aidocumentagent.ingestion.messaging.DocumentIngestionMessage;
import com.wxx.aidocumentagent.ingestion.messaging.IngestionMessageValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** outbox 的租约、publisher confirm 回写和发布失败补偿都在数据库事务中完成。 */
@Service
public class IngestionOutboxLifecycleService {

    private final DocumentIngestionOutboxEventRepository outboxRepository;
    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentBatchTaskRepository batchTaskRepository;
    private final DocumentRepository documentRepository;
    private final IngestionProperties properties;

    public IngestionOutboxLifecycleService(DocumentIngestionOutboxEventRepository outboxRepository,
                                           DocumentIngestionJobRepository jobRepository,
                                           DocumentBatchTaskRepository batchTaskRepository,
                                           DocumentRepository documentRepository,
                                           IngestionProperties properties) {
        this.outboxRepository = outboxRepository;
        this.jobRepository = jobRepository;
        this.batchTaskRepository = batchTaskRepository;
        this.documentRepository = documentRepository;
        this.properties = properties;
    }

    @Transactional
    public Optional<OutboxDispatchEnvelope> claim(String eventId) {
        DocumentIngestionOutboxEvent event = outboxRepository.findLockedByEventId(eventId).orElse(null);
        if (event == null || !event.claim(LocalDateTime.now())) {
            return Optional.empty();
        }
        return Optional.of(toEnvelope(event));
    }

    @Transactional
    public void markPublished(String eventId) {
        DocumentIngestionOutboxEvent event = outboxRepository.findLockedByEventId(eventId).orElse(null);
        if (event == null || event.getStatus() == OutboxEventStatus.PUBLISHED) {
            return;
        }
        event.markPublished(LocalDateTime.now());
        if (event.getDispatchMode() != OutboxDispatchMode.PRIMARY) {
            return;
        }
        if (event.getMessageType() == OutboxMessageType.DOCUMENT_COORDINATOR) {
            markDocumentJobQueued(event);
        }
        else {
            markBatchQueued(event);
        }
    }

    @Transactional
    public void releaseAfterPublishFailure(String eventId) {
        DocumentIngestionOutboxEvent event = outboxRepository.findLockedByEventId(eventId).orElse(null);
        if (event == null) {
            return;
        }
        int dispatchAttempt = event.getDispatchAttempt();
        LocalDateTime nextAttemptAt = LocalDateTime.now().plus(java.time.Duration.ofMillis(
                properties.retryDelayMillis(dispatchAttempt)));
        boolean exhausted = event.releaseAfterPublishFailure(properties.getOutbox().getMaxPublishAttempts(),
                nextAttemptAt, IngestionErrorCode.INGESTION_PUBLISH_FAILURE.code(),
                IngestionErrorCode.INGESTION_PUBLISH_FAILURE.defaultMessage());
        if (exhausted) {
            markTargetFailed(event, IngestionErrorCode.INGESTION_PUBLISH_FAILURE.code(),
                    IngestionErrorCode.INGESTION_PUBLISH_FAILURE.defaultMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<String> dispatchableEventIds() {
        return outboxRepository.findDispatchableEventIds(OutboxEventStatus.PENDING, LocalDateTime.now(),
                PageRequest.of(0, properties.getOutbox().getBatchSize()));
    }

    @Transactional
    public void releaseExpiredDispatchLeases() {
        LocalDateTime now = LocalDateTime.now();
        List<String> eventIds = outboxRepository.findExpiredLeaseEventIds(OutboxEventStatus.DISPATCHING,
                now.minus(properties.getOutbox().getDispatchLease()),
                PageRequest.of(0, properties.getOutbox().getBatchSize()));
        for (String eventId : eventIds) {
            outboxRepository.findLockedByEventId(eventId).ifPresent(event -> event.releaseExpiredLease(now));
        }
    }

    private OutboxDispatchEnvelope toEnvelope(DocumentIngestionOutboxEvent event) {
        try {
            UUID eventId = UUID.fromString(event.getEventId());
            UUID jobId = UUID.fromString(event.getJobId());
            if (event.getMessageType() == OutboxMessageType.DOCUMENT_COORDINATOR) {
                Instant occurredAt = event.getCreatedAt() == null ? Instant.now()
                        : event.getCreatedAt().toInstant(ZoneOffset.UTC);
                return new OutboxDispatchEnvelope(event.getEventId(), event.getMessageType(), event.getDispatchMode(),
                        event.getRetryDelayMillis(), new DocumentIngestionMessage(eventId, jobId, event.getDocumentId(),
                        event.getKnowledgeBaseId(), com.wxx.aidocumentagent.ingestion.domain.IngestionOperation.PARSE_AND_SPLIT,
                        event.getMessageAttempt(), occurredAt, DocumentIngestionMessage.SCHEMA_VERSION));
            }
            DocumentBatchTask task = batchTaskRepository.findByBatchIdAndKnowledgeBaseId(event.getBatchId(),
                    event.getKnowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("outbox batch不存在"));
            return new OutboxDispatchEnvelope(event.getEventId(), event.getMessageType(), event.getDispatchMode(),
                    event.getRetryDelayMillis(), new ChunkBatchMessage(eventId, jobId, UUID.fromString(task.getBatchId()),
                    task.getDocumentId(), task.getKnowledgeBaseId(), task.getChunkFrom(), task.getChunkTo(), task.getStage(),
                    event.getMessageAttempt(), ChunkBatchMessage.SCHEMA_VERSION));
        }
        catch (IllegalArgumentException exception) {
            throw new IngestionMessageValidationException("outbox事件标识不合法", exception);
        }
    }

    private void markDocumentJobQueued(DocumentIngestionOutboxEvent event) {
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(event.getJobId(),
                event.getKnowledgeBaseId()).orElse(null);
        if (job == null) {
            return;
        }
        job.markQueued(LocalDateTime.now());
        documentRepository.findByIdAndKnowledgeBaseId(event.getDocumentId(), event.getKnowledgeBaseId())
                .ifPresent(document -> document.markQueued());
    }

    private void markBatchQueued(DocumentIngestionOutboxEvent event) {
        DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(event.getBatchId(),
                event.getKnowledgeBaseId()).orElse(null);
        if (task != null) {
            task.markQueued(LocalDateTime.now());
        }
    }

    private void markTargetFailed(DocumentIngestionOutboxEvent event, String code, String message) {
        LocalDateTime now = LocalDateTime.now();
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(event.getJobId(),
                event.getKnowledgeBaseId()).orElse(null);
        Document document = documentRepository.findByIdAndKnowledgeBaseId(event.getDocumentId(),
                event.getKnowledgeBaseId()).orElse(null);
        if (job == null || document == null || job.getStatus().isTerminal()) {
            return;
        }
        if (event.getMessageType() == OutboxMessageType.CHUNK_BATCH) {
            DocumentBatchTask task = batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(event.getBatchId(),
                    event.getKnowledgeBaseId()).orElse(null);
            if (task != null && task.getStatus() != BatchTaskStatus.COMPLETED) {
                task.markFailed(code, message, now);
                batchTaskRepository.flush();
                int completed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(
                        job.getJobId(), job.getKnowledgeBaseId(), BatchTaskStatus.COMPLETED));
                int failed = Math.toIntExact(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(
                        job.getJobId(), job.getKnowledgeBaseId(), BatchTaskStatus.FAILED));
                job.updateBatchSummary(job.getTotalBatchCount(), completed, failed);
            }
        }
        job.markFailed(code, message, now);
        document.markFailed(code, message);
    }
}
