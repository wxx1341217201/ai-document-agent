package com.wxx.aidocumentagent.ingestion.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxEventStatus;
import com.wxx.aidocumentagent.ingestion.domain.OutboxMessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 与任务状态同事务写入，允许安全重放的消息发布记录。 */
@Entity
@Table(name = "document_ingestion_outbox_event")
public class DocumentIngestionOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, updatable = false)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 32, updatable = false)
    private OutboxMessageType messageType;

    @Enumerated(EnumType.STRING)
    @Column(name = "dispatch_mode", nullable = false, length = 16, updatable = false)
    private OutboxDispatchMode dispatchMode;

    @Column(name = "job_id", nullable = false, length = 36, updatable = false)
    private String jobId;

    @Column(name = "batch_id", length = 36, updatable = false)
    private String batchId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private long documentId;

    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private long knowledgeBaseId;

    @Column(name = "message_attempt", nullable = false, updatable = false)
    private int messageAttempt;

    @Column(name = "dispatch_attempt", nullable = false)
    private int dispatchAttempt;

    @Column(name = "retry_delay_millis", nullable = false, updatable = false)
    private long retryDelayMillis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxEventStatus status;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DocumentIngestionOutboxEvent() {
    }

    private DocumentIngestionOutboxEvent(OutboxMessageType messageType, OutboxDispatchMode dispatchMode,
                                          DocumentIngestionJob job, DocumentBatchTask batchTask,
                                          int messageAttempt, long retryDelayMillis) {
        this.eventId = UUID.randomUUID().toString();
        this.messageType = messageType;
        this.dispatchMode = dispatchMode;
        this.jobId = job.getJobId();
        this.batchId = batchTask == null ? null : batchTask.getBatchId();
        this.documentId = job.getDocumentId();
        this.knowledgeBaseId = job.getKnowledgeBaseId();
        this.messageAttempt = messageAttempt;
        this.retryDelayMillis = retryDelayMillis;
        this.status = OutboxEventStatus.PENDING;
        this.availableAt = LocalDateTime.now();
    }

    public static DocumentIngestionOutboxEvent document(DocumentIngestionJob job, int messageAttempt,
                                                          OutboxDispatchMode dispatchMode, long retryDelayMillis) {
        return new DocumentIngestionOutboxEvent(OutboxMessageType.DOCUMENT_COORDINATOR, dispatchMode, job, null,
                messageAttempt, retryDelayMillis);
    }

    public static DocumentIngestionOutboxEvent batch(DocumentIngestionJob job, DocumentBatchTask batchTask,
                                                       int messageAttempt, OutboxDispatchMode dispatchMode,
                                                       long retryDelayMillis) {
        return new DocumentIngestionOutboxEvent(OutboxMessageType.CHUNK_BATCH, dispatchMode, job, batchTask,
                messageAttempt, retryDelayMillis);
    }

    @PrePersist
    void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }

    public boolean claim(LocalDateTime now) {
        if (status != OutboxEventStatus.PENDING || availableAt.isAfter(now)) {
            return false;
        }
        status = OutboxEventStatus.DISPATCHING;
        lockedAt = now;
        dispatchAttempt++;
        return true;
    }

    public boolean releaseAfterPublishFailure(int maxAttempts, LocalDateTime nextAvailableAt,
                                              String code, String message) {
        if (status != OutboxEventStatus.DISPATCHING) {
            return false;
        }
        setError(code, message);
        lockedAt = null;
        if (dispatchAttempt >= maxAttempts) {
            status = OutboxEventStatus.FAILED;
            return true;
        }
        status = OutboxEventStatus.PENDING;
        availableAt = nextAvailableAt;
        return false;
    }

    public void releaseExpiredLease(LocalDateTime now) {
        if (status == OutboxEventStatus.DISPATCHING) {
            status = OutboxEventStatus.PENDING;
            availableAt = now;
            lockedAt = null;
        }
    }

    public void markPublished(LocalDateTime now) {
        if (status != OutboxEventStatus.DISPATCHING) {
            throw new IllegalStateException("只有DISPATCHING outbox事件可以确认发布");
        }
        status = OutboxEventStatus.PUBLISHED;
        publishedAt = now;
        lockedAt = null;
        clearError();
    }

    private void setError(String code, String message) {
        errorCode = code;
        errorMessage = message;
    }

    private void clearError() {
        errorCode = null;
        errorMessage = null;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public OutboxMessageType getMessageType() { return messageType; }
    public OutboxDispatchMode getDispatchMode() { return dispatchMode; }
    public String getJobId() { return jobId; }
    public String getBatchId() { return batchId; }
    public long getDocumentId() { return documentId; }
    public long getKnowledgeBaseId() { return knowledgeBaseId; }
    public int getMessageAttempt() { return messageAttempt; }
    public int getDispatchAttempt() { return dispatchAttempt; }
    public long getRetryDelayMillis() { return retryDelayMillis; }
    public OutboxEventStatus getStatus() { return status; }
    public LocalDateTime getAvailableAt() { return availableAt; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
