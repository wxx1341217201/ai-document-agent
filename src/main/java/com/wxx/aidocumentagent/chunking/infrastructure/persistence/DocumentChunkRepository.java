package com.wxx.aidocumentagent.chunking.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByKnowledgeBaseIdAndDocumentIdOrderByChunkIndexAsc(long knowledgeBaseId, long documentId);

    List<DocumentChunk> findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(
            long knowledgeBaseId, long documentId, int chunkFrom, int chunkTo);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DocumentChunk chunk where chunk.knowledgeBaseId = :knowledgeBaseId "
            + "and chunk.documentId = :documentId")
    int deleteByKnowledgeBaseIdAndDocumentId(@Param("knowledgeBaseId") long knowledgeBaseId,
                                             @Param("documentId") long documentId);
}
