package com.wxx.aidocumentagent.agent;

import java.time.Instant;
import java.util.Objects;

/** 仅暴露状态和稳定错误码，不包含提示词、工具原始输出或隐藏推理。 */
public record AgentEvent(String traceId, long knowledgeBaseId, Long conversationId, AgentMode mode,
                         Kind kind, String stepId, AgentErrorCode errorCode, Instant occurredAt) {

    public AgentEvent {
        if (traceId == null || traceId.isBlank() || knowledgeBaseId <= 0L) {
            throw new IllegalArgumentException("事件追踪或知识库标识不合法");
        }
        Objects.requireNonNull(mode, "mode不能为空");
        Objects.requireNonNull(kind, "kind不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt不能为空");
        if (stepId != null && !AgentStepSummary.machineIdentifier(stepId)) {
            throw new IllegalArgumentException("事件步骤标识不合法");
        }
    }

    public enum Kind {
        STARTED, RETRIEVING, GENERATING, STEP_UPDATED, TOOL_UPDATED, COMPLETED, FAILED
    }
}
