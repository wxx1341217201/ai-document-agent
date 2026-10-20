package com.wxx.aidocumentagent.agent.execution.api.dto;

import java.time.LocalDateTime;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.ExecutionStatus;

/** 列表摘要不读取问题、模型结果、执行令牌或工具参数。 */
public record ExecutionSummaryResponse(String executionId, Long conversationId, long knowledgeBaseId,
                                       AgentMode agentMode, ExecutionStatus status, String currentStep,
                                       LocalDateTime startedAt, LocalDateTime updatedAt) {
}
