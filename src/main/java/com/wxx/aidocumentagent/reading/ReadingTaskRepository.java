package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface ReadingTaskRepository extends JpaRepository<ReadingTask, String> {
    Optional<ReadingTask> findByIdAndKnowledgeBaseId(String id, long knowledgeBaseId);
    Optional<ReadingTask> findByKnowledgeBaseIdAndRequestKey(long knowledgeBaseId, String requestKey);
    Page<Summary> findAllProjectedByKnowledgeBaseId(long knowledgeBaseId, Pageable pageable);
    Page<Summary> findAllProjectedByKnowledgeBaseIdAndSessionId(long knowledgeBaseId, String sessionId, Pageable pageable);
    Optional<ReadingTask> findFirstByStatusOrderByCreatedAtAscIdAsc(String status);
    boolean existsByStatus(String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReadingTask t where t.id = :id and t.knowledgeBaseId = :kb")
    Optional<ReadingTask> lock(String id, long kb);
    @Modifying
    @Query("update ReadingTask t set t.status='INTERRUPTED', t.runToken=null, t.leaseUntil=null, "
            + "t.errorMessage='执行进程中断，等待手动恢复', t.updatedAt=:now, t.version=t.version+1 "
            + "where t.status='RUNNING' and t.leaseUntil < :now")
    int interruptExpired(Instant now);
    interface Summary {
        String getId(); long getKnowledgeBaseId(); String getSessionId(); String getGoal();
        String getStatus(); String getCompletion(); int getRoundNo(); String getLatestResultId();
        Instant getCreatedAt(); Instant getUpdatedAt();
    }
}
