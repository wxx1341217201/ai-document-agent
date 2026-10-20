package com.wxx.aidocumentagent.agent.execution.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {
    List<ToolInvocation> findByExecutionIdAndKnowledgeBaseIdOrderByIdAsc(String executionId, long knowledgeBaseId);
}
