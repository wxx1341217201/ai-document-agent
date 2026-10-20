package com.wxx.aidocumentagent.agent.execution.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.wxx.aidocumentagent.agent.AgentMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.wxx.aidocumentagent.agent.execution.ExecutionStatus;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, String> {
    Page<Summary> findAllProjectedBy(Pageable pageable);
    Page<Summary> findAllProjectedByStatus(ExecutionStatus status, Pageable pageable);
    Page<Summary> findAllProjectedByAgentMode(AgentMode agentMode, Pageable pageable);
    Page<Summary> findAllProjectedByStatusAndAgentMode(ExecutionStatus status, AgentMode agentMode, Pageable pageable);

    interface Summary {
        String getExecutionId();
        Long getConversationId();
        long getKnowledgeBaseId();
        AgentMode getAgentMode();
        ExecutionStatus getStatus();
        String getCurrentStep();
        LocalDateTime getStartedAt();
        LocalDateTime getUpdatedAt();
    }

    Optional<AgentExecution> findByRequestKeyAndKnowledgeBaseId(String requestKey, long knowledgeBaseId);
    Optional<AgentExecution> findByExecutionIdAndKnowledgeBaseId(String executionId, long knowledgeBaseId);
    List<AgentExecution> findByStatusAndLeaseUntilBefore(ExecutionStatus status, LocalDateTime cutoff, Pageable page);
    List<AgentExecution> findByStatusOrderByCreatedAtAsc(ExecutionStatus status, Pageable page);
    List<AgentExecution> findByStatusInAndDeadlineAtBefore(List<ExecutionStatus> statuses,
                                                            LocalDateTime cutoff, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from AgentExecution execution where execution.executionId = :id "
            + "and execution.knowledgeBaseId = :knowledgeBaseId")
    Optional<AgentExecution> findLocked(@Param("id") String id, @Param("knowledgeBaseId") long knowledgeBaseId);
}
