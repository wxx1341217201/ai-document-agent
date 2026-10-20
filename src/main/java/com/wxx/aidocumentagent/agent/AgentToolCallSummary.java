package com.wxx.aidocumentagent.agent;

import java.util.Objects;
import java.util.Map;

/** 工具调用仅保留工具标识、状态、耗时和安全错误码，不保留入参或原始输出。 */
public record AgentToolCallSummary(String toolName, AgentStepSummary.Status status, Long elapsedMillis,
                                   AgentErrorCode errorCode, Map<String, String> argumentsSummary) {

    public AgentToolCallSummary(String toolName, AgentStepSummary.Status status, Long elapsedMillis,
                                AgentErrorCode errorCode) {
        this(toolName, status, elapsedMillis, errorCode, Map.of());
    }

    public AgentToolCallSummary {
        if (!AgentStepSummary.machineIdentifier(toolName)) {
            throw new IllegalArgumentException("toolName不合法");
        }
        Objects.requireNonNull(status, "status不能为空");
        argumentsSummary = argumentsSummary == null ? Map.of() : Map.copyOf(argumentsSummary);
        if (elapsedMillis != null && elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsedMillis不能为负数");
        }
        if (status != AgentStepSummary.Status.FAILED && errorCode != null) {
            throw new IllegalArgumentException("仅失败工具调用可携带错误码");
        }
        if (status == AgentStepSummary.Status.FAILED && errorCode == null) {
            throw new IllegalArgumentException("失败工具调用必须携带错误码");
        }
    }
}
