package com.wxx.aidocumentagent.agent.execution;

/** Optional observer after the result transaction commits; contains no model reasoning or credentials. */
@FunctionalInterface
public interface ExecutionCheckpointObserver {
    ExecutionCheckpointObserver NO_OP = (executionId, knowledgeBaseId) -> { };

    void afterResultCheckpoint(String executionId, long knowledgeBaseId);
}
