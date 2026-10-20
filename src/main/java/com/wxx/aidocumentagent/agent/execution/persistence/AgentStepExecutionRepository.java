package com.wxx.aidocumentagent.agent.execution.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStepExecutionRepository extends JpaRepository<AgentStepExecution, Long> {
    Optional<AgentStepExecution> findByExecutionIdAndKnowledgeBaseIdAndStepId(String executionId,
                                                                                long knowledgeBaseId, String stepId);
    List<AgentStepExecution> findByExecutionIdAndKnowledgeBaseIdOrderByIdAsc(String executionId, long knowledgeBaseId);
}
