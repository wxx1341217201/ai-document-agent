package com.wxx.aidocumentagent.agent.execution.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.ExecutionStatus;
import tools.jackson.databind.JsonNode;

public record ExecutionResponse(String executionId, long knowledgeBaseId, Long conversationId,
                                AgentMode agentMode, ExecutionStatus status, String currentStep, long version,
                                LocalDateTime deadlineAt, boolean cancelRequested, boolean retryable,
                                JsonNode result, String errorCode, List<Step> steps, List<Tool> toolCalls,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
    public record Step(String stepId, String kind, String operation, String status, JsonNode dependsOn,
                       String errorCode) { }
    public record Tool(String invocationKey, String toolName, String status, JsonNode argumentsSummary,
                       Long elapsedMillis, String errorCode) { }
}
