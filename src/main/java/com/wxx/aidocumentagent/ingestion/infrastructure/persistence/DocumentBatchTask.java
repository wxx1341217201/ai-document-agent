package com.wxx.aidocumentagent.ingestion.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
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

/** 以稳定 chunk index 区间描述的可并行索引子任务。 */
@Entity
@Table(name = "document_batch_task")
public class DocumentBatchTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 36, updatable = false)
    private String batchId;

    @Column(name = "job_id", nullable = false, length = 36, updatable = false)
    private String jobId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private long documentId;

    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private long knowledgeBaseId;

    @Column(name = "batch_no", nullable = false, updatable = false)
    private int batchNo;

    @Column(name = "chunk_from", nullable = false, updatable = false)
    private int chunkFrom;

    @Column(name = "chunk_to", nullable = false, updatable = false)
    private int chunkTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private ChunkBatchStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BatchTaskStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "enqueued_at")
    private LocalDateTime enqueuedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

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

    protected DocumentBatchTask() {
    }

    private DocumentBatchTask(DocumentIngestionJob job, int batchNo, int chunkFrom, int chunkTo) {
        if (batchNo < 0 || chunkFrom < 0 || chunkTo < chunkFrom) {
            throw new IllegalArgumentException("batch区间不合法");
        }
        this.batchId = UUID.randomUUID().toString();
        this.jobId = job.getJobId();
        this.documentId = job.getDocumentId();
        this.knowledgeBaseId = job.getKnowledgeBaseId();
        this.batchNo = batchNo;
        this.chunkFrom = chunkFrom;
        this.chunkTo = chunkTo;
        this.stage = ChunkBatchStage.INDEX;
        this.status = BatchTaskStatus.PENDING_DISPATCH;
    }

    public static DocumentBatchTask create(DocumentIngestionJob job, int batchNo, int chunkFrom, int chunkTo) {
        return new DocumentBatchTask(job, batchNo, chunkFrom, chunkTo);
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

    public void markQueued(LocalDateTime now) {
        if (status == BatchTaskStatus.PENDING_DISPATCH || status == BatchTaskStatus.RETRYING) {
            status = BatchTaskStatus.QUEUED;
            if (enqueuedAt == null) {
                enqueuedAt = now;
            }
            clearError();
        }
    }

    public boolean beginProcessing(LocalDateTime now) {
        if (status != BatchTaskStatus.QUEUED && status != BatchTaskStatus.RETRYING) {
            return false;
        }
        status = BatchTaskStatus.PROCESSING;
        if (startedAt == null) {
            startedAt = now;
        }
        clearError();
        return true;
    }

    public void markRetrying(int nextAttempt, String code, String message) {
        if (status.isTerminal()) {
            throw new IllegalStateException("终态batch不能进入重试");
        }
        if (nextAttempt < attempt) {
            throw new IllegalStateException("batch重试次数不能回退");
        }
        attempt = nextAttempt;
        status = BatchTaskStatus.RETRYING;
        setError(code, message);
    }

    public void markCompleted(LocalDateTime now) {
        if (status != BatchTaskStatus.PROCESSING) {
            throw new IllegalStateException("batch不能从" + status + "转换为COMPLETED");
        }
        status = BatchTaskStatus.COMPLETED;
        completedAt = now;
        clearError();
    }

    public void markFailed(String code, String message, LocalDateTime now) {
        if (status == BatchTaskStatus.COMPLETED) {
            throw new IllegalStateException("已完成batch不能标记为失败");
        }
        status = BatchTaskStatus.FAILED;
        completedAt = now;
        setError(code, message);
    }

    public void prepareManualRetry() {
        if (status == BatchTaskStatus.COMPLETED) {
            throw new IllegalStateException("已完成batch不能手动重试");
        }
        status = BatchTaskStatus.RETRYING;
        attempt = 0;
        completedAt = null;
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
    public String getBatchId() { return batchId; }
    public String getJobId() { return jobId; }
    public long getDocumentId() { return documentId; }
    public long getKnowledgeBaseId() { return knowledgeBaseId; }
    public int getBatchNo() { return batchNo; }
    public int getChunkFrom() { return chunkFrom; }
    public int getChunkTo() { return chunkTo; }
    public ChunkBatchStage getStage() { return stage; }
    public BatchTaskStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public LocalDateTime getEnqueuedAt() { return enqueuedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
