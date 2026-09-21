package com.wxx.aidocumentagent.ingestion.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentIngestionJobRepository extends JpaRepository<DocumentIngestionJob, Long> {

    Optional<DocumentIngestionJob> findByDocumentIdAndKnowledgeBaseId(long documentId, long knowledgeBaseId);

    Optional<DocumentIngestionJob> findByJobIdAndKnowledgeBaseId(String jobId, long knowledgeBaseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from DocumentIngestionJob job where job.jobId = :jobId "
            + "and job.knowledgeBaseId = :knowledgeBaseId")
    Optional<DocumentIngestionJob> findLockedByJobIdAndKnowledgeBaseId(@Param("jobId") String jobId,
                                                                         @Param("knowledgeBaseId") long knowledgeBaseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from DocumentIngestionJob job where job.documentId = :documentId "
            + "and job.knowledgeBaseId = :knowledgeBaseId")
    Optional<DocumentIngestionJob> findLockedByDocumentIdAndKnowledgeBaseId(@Param("documentId") long documentId,
                                                                              @Param("knowledgeBaseId") long knowledgeBaseId);

    List<DocumentIngestionJob> findByStatusAndStartedAtBefore(IngestionJobStatus status, LocalDateTime startedBefore);
}
