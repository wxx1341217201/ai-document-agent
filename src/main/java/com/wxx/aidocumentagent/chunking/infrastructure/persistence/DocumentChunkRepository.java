package com.wxx.aidocumentagent.chunking.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    Page<ContentSummary> findAllProjectedByKnowledgeBaseIdAndDocumentId(long knowledgeBaseId, long documentId, Pageable page);

    interface ContentSummary {
        Long getId();
        int getChunkIndex();
        String getContent();
        Integer getPageFrom();
        Integer getPageTo();
        String getSectionTitle();
        String getContentHash();
    }

    Page<ContentSummary> findAllProjectedByKnowledgeBaseIdAndDocumentIdAndChunkIndexGreaterThanEqual(
            long knowledgeBaseId, long documentId, int chunkIndex, Pageable page);

    Page<VersionSummary> findVersionsByKnowledgeBaseIdAndDocumentId(long knowledgeBaseId, long documentId, Pageable page);

    interface VersionSummary {
        Long getId();
        int getChunkIndex();
        String getContentHash();
        Integer getPageFrom();
        Integer getPageTo();
        String getSectionTitle();
        java.time.LocalDateTime getUpdatedAt();
    }


    Optional<DocumentChunk> findByIdAndKnowledgeBaseId(long id, long knowledgeBaseId);

    boolean existsByKnowledgeBaseIdAndDocumentId(long knowledgeBaseId, long documentId);

    List<DocumentChunk> findByKnowledgeBaseIdAndDocumentIdOrderByChunkIndexAsc(long knowledgeBaseId, long documentId);

    List<DocumentChunk> findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(
            long knowledgeBaseId, long documentId, int chunkFrom, int chunkTo);

    /** RAG 引用只允许从当前知识库持久化的真实 chunk 回填，不能相信检索索引回传的元数据。 */
    List<DocumentChunk> findByKnowledgeBaseIdAndIdIn(long knowledgeBaseId, Collection<Long> chunkIds);

    /** Accuracy benchmark 冻结完整语料，包含无答案题复核所需的未标注 chunks。 */
    List<DocumentChunk> findByKnowledgeBaseIdOrderByIdAsc(long knowledgeBaseId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DocumentChunk chunk where chunk.knowledgeBaseId = :knowledgeBaseId "
            + "and chunk.documentId = :documentId")
    int deleteByKnowledgeBaseIdAndDocumentId(@Param("knowledgeBaseId") long knowledgeBaseId,
                                             @Param("documentId") long documentId);
}
