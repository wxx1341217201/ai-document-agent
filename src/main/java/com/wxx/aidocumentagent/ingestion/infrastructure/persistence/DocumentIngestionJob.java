package com.wxx.aidocumentagent.ingestion.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionOperation;
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

/** 一次文档解析、切分和 batch 聚合的数据库协调记录。 */
@Entity
@Table(name = "document_ingestion_job")
public class DocumentIngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, length = 36, updatable = false)
    private String jobId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private long documentId;

    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private long knowledgeBaseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private IngestionOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngestionJobStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "total_batch_count", nullable = false)
    private int totalBatchCount;

    @Column(name = "completed_batch_count", nullable = false)
    private int completedBatchCount;

    @Column(name = "failed_batch_count", nullable = false)
    private int failedBatchCount;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "enqueued_at")
    private LocalDateTime enqueuedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DocumentIngestionJob() {
    }

    private DocumentIngestionJob(long knowledgeBaseId, long documentId, IngestionOperation operation) {
        this.jobId = UUID.randomUUID().toString();
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.operation = operation;
        this.status = IngestionJobStatus.UPLOADED;
    }

    public static DocumentIngestionJob create(long knowledgeBaseId, long documentId) {
        return new DocumentIngestionJob(knowledgeBaseId, documentId, IngestionOperation.PARSE_AND_SPLIT);
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

    public boolean beginProcessing(LocalDateTime now) {
        if (status != IngestionJobStatus.QUEUED && status != IngestionJobStatus.RETRYING) {
            return false;
        }
        status = IngestionJobStatus.PROCESSING;
        if (startedAt == null) {
            startedAt = now;
        }
        clearError();
        return true;
    }

    public void markQueued(LocalDateTime now) {
        if (status == IngestionJobStatus.UPLOADED || status == IngestionJobStatus.RETRYING) {
            status = IngestionJobStatus.QUEUED;
            if (enqueuedAt == null) {
                enqueuedAt = now;
            }
            clearError();
        }
    }

    public void markRetrying(int nextAttempt, String code, String message) {
        if (status.isTerminal()) {
            throw new IllegalStateException("终态摄取任务不能进入重试");
        }
        if (nextAttempt < attempt) {
            throw new IllegalStateException("摄取重试次数不能回退");
        }
        attempt = nextAttempt;
        status = IngestionJobStatus.RETRYING;
        setError(code, message);
    }

    public void defineBatches(int batchCount) {
        if (status != IngestionJobStatus.PROCESSING || batchCount < 0 || totalBatchCount != 0) {
            throw new IllegalStateException("摄取任务不能在当前状态创建batch");
        }
        totalBatchCount = batchCount;
    }

    public void updateBatchSummary(int total, int completed, int failed) {
        if (total != totalBatchCount || completed < 0 || failed < 0 || completed + failed > total) {
            throw new IllegalStateException("batch聚合计数不合法");
        }
        completedBatchCount = completed;
        failedBatchCount = failed;
    }

    public void markReady(LocalDateTime now) {
        if (status != IngestionJobStatus.PROCESSING && status != IngestionJobStatus.RETRYING) {
            throw new IllegalStateException("摄取任务不能从" + status + "转换为READY");
        }
        if (failedBatchCount != 0 || completedBatchCount != totalBatchCount) {
            throw new IllegalStateException("仍有未完成或失败batch，不能标记READY");
        }
        status = IngestionJobStatus.READY;
        completedAt = now;
        clearError();
    }

    public void markFailed(String code, String message, LocalDateTime now) {
        if (status == IngestionJobStatus.READY) {
            throw new IllegalStateException("READY摄取任务不能标记为失败");
        }
        status = IngestionJobStatus.FAILED;
        completedAt = now;
        setError(code, message);
    }

    public void prepareManualRetry() {
        if (status != IngestionJobStatus.FAILED) {
            throw new IllegalStateException("只有FAILED摄取任务可以手动重试");
        }
        status = IngestionJobStatus.RETRYING;
        attempt = 0;
        failedBatchCount = 0;
        completedAt = null;
        clearError();
    }

    private void setError(String code, String message) {
        this.errorCode = code;
        this.errorMessage = message;
    }

    private void clearError() {
        this.errorCode = null;
        this.errorMessage = null;
    }

    public Long getId() { return id; }
    public String getJobId() { return jobId; }
    public long getDocumentId() { return documentId; }
    public long getKnowledgeBaseId() { return knowledgeBaseId; }
    public IngestionOperation getOperation() { return operation; }
    public IngestionJobStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public int getTotalBatchCount() { return totalBatchCount; }
    public int getCompletedBatchCount() { return completedBatchCount; }
    public int getFailedBatchCount() { return failedBatchCount; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getEnqueuedAt() { return enqueuedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
