package com.wxx.aidocumentagent.chunking.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wxx.aidocumentagent.chunking.TextChunk;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * document_chunk 的持久化模型，冗余 knowledgeBaseId 只能通过受限应用服务写入。
 */
@Entity
@Table(name = "document_chunk")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_base_id", nullable = false)
    private Long knowledgeBaseId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "page_from")
    private Integer pageFrom;

    @Column(name = "page_to")
    private Integer pageTo;

    @Column(name = "section_title", length = 512)
    private String sectionTitle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "json")
    private Map<String, String> metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DocumentChunk() {
    }

    private DocumentChunk(long knowledgeBaseId, long documentId, TextChunk textChunk) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.chunkIndex = textChunk.chunkIndex();
        this.content = textChunk.content();
        this.contentHash = textChunk.contentHash();
        this.tokenCount = textChunk.tokenCount();
        this.pageFrom = textChunk.pageFrom();
        this.pageTo = textChunk.pageTo();
        this.sectionTitle = textChunk.sectionTitle();
        this.metadataJson = new LinkedHashMap<>(textChunk.metadata());
    }

    public static DocumentChunk create(long knowledgeBaseId, long documentId, TextChunk textChunk) {
        return new DocumentChunk(knowledgeBaseId, documentId, textChunk);
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

    public Long getDocumentId() {
        return documentId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public Integer getPageFrom() {
        return pageFrom;
    }

    public Integer getPageTo() {
        return pageTo;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public Map<String, String> getMetadataJson() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadataJson));
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
