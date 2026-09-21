package com.wxx.aidocumentagent.ingestion.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentBatchTaskRepository extends JpaRepository<DocumentBatchTask, Long> {

    List<DocumentBatchTask> findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(String jobId, long knowledgeBaseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from DocumentBatchTask task where task.batchId = :batchId "
            + "and task.knowledgeBaseId = :knowledgeBaseId")
    Optional<DocumentBatchTask> findLockedByBatchIdAndKnowledgeBaseId(@Param("batchId") String batchId,
                                                                        @Param("knowledgeBaseId") long knowledgeBaseId);

    Optional<DocumentBatchTask> findByBatchIdAndKnowledgeBaseId(String batchId, long knowledgeBaseId);

    long countByJobIdAndKnowledgeBaseIdAndStatus(String jobId, long knowledgeBaseId, BatchTaskStatus status);

    List<DocumentBatchTask> findByStatusAndStartedAtBefore(BatchTaskStatus status, LocalDateTime startedBefore);
}
