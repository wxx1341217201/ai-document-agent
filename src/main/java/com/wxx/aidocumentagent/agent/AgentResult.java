package com.wxx.aidocumentagent.agent;

import java.util.List;
import java.util.Objects;

/** 只包含最终答案、可验证引用、结构化状态、工具摘要、安全错误与用量。 */
public record AgentResult(String answer, List<AgentCitation> citations, List<AgentStepSummary> steps,
                          List<AgentToolCallSummary> toolCalls, Status status, AgentUsage usage,
                          AgentFailure failure, String modelName) {

    public AgentResult(String answer, List<AgentCitation> citations, List<AgentStepSummary> steps,
                       List<AgentToolCallSummary> toolCalls, Status status, AgentUsage usage, AgentFailure failure) {
        this(answer, citations, steps, toolCalls, status, usage, failure, null);
    }

    public AgentResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        Objects.requireNonNull(status, "status不能为空");
        Objects.requireNonNull(usage, "usage不能为空");
        if (modelName != null && modelName.isBlank()) {
            throw new IllegalArgumentException("modelName不能为空字符串");
        }
        if (status == Status.COMPLETED && (answer == null || answer.isBlank() || failure != null)) {
            throw new IllegalArgumentException("完成的结果必须有最终答案且无错误");
        }
        if (status != Status.COMPLETED && (answer != null || failure == null)) {
            throw new IllegalArgumentException("未完成的结果只能携带安全错误，不能携带答案");
        }
    }

    public enum Status {
        COMPLETED, FAILED, CANCELLED, TIMED_OUT
    }
}
