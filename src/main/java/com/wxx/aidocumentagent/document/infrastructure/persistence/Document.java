package com.wxx.aidocumentagent.document.infrastructure.persistence;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;
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

/**
 * 已上传文档元数据的持久化模型，绝不作为 API DTO 使用。
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_base_id", nullable = false)
    private Long knowledgeBaseId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "storage_key", nullable = false, length = 128)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(nullable = false, length = 16)
    private String extension;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentStatus status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Document() {
    }

    private Document(Long knowledgeBaseId, String originalName, String storageKey, String contentType, String extension,
                     long sizeBytes, String sha256) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.originalName = originalName;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.extension = extension;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.status = DocumentStatus.UPLOADED;
    }

    public static Document uploaded(Long knowledgeBaseId, String originalName, String storageKey, String contentType,
                                    String extension, long sizeBytes, String sha256) {
        return new Document(knowledgeBaseId, originalName, storageKey, contentType, extension, sizeBytes, sha256);
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

    public Long getId() {
        return id;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public String getExtension() {
        return extension;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 摄取状态只能由摄取应用服务推进；删除仍由既有删除流程处理。
     */
    public void markQueued() {
        if (status == DocumentStatus.UPLOADED || status == DocumentStatus.RETRYING || status == DocumentStatus.FAILED) {
            status = DocumentStatus.QUEUED;
            clearError();
        }
    }

    public void markProcessing() {
        if (status == DocumentStatus.UPLOADED || status == DocumentStatus.QUEUED || status == DocumentStatus.RETRYING) {
            status = DocumentStatus.PROCESSING;
            clearError();
        }
    }

    public void markRetrying(String code, String message) {
        if (status != DocumentStatus.READY && status != DocumentStatus.DELETED) {
            status = DocumentStatus.RETRYING;
            setError(code, message);
        }
    }

    public void markReady() {
        if (status != DocumentStatus.PROCESSING && status != DocumentStatus.RETRYING) {
            throw new IllegalStateException("文档不能从" + status + "转换为READY");
        }
        status = DocumentStatus.READY;
        clearError();
    }

    public void markFailed(String code, String message) {
        if (status != DocumentStatus.READY && status != DocumentStatus.DELETED) {
            status = DocumentStatus.FAILED;
            setError(code, message);
        }
    }

    private void setError(String code, String message) {
        this.errorCode = code;
        this.errorMessage = message;
    }

    private void clearError() {
        this.errorCode = null;
        this.errorMessage = null;
    }
}
