package com.wxx.aidocumentagent.agent;

import java.util.Objects;

/** 结构化计划与步骤状态，仅允许稳定操作标识，不能承载自由文本思维链。 */
public record AgentStepSummary(String stepId, Kind kind, String operation, Status status,
                               AgentErrorCode errorCode, java.util.List<String> dependsOn) {

    public AgentStepSummary(String stepId, Kind kind, String operation, Status status,
                            AgentErrorCode errorCode) {
        this(stepId, kind, operation, status, errorCode, java.util.List.of());
    }

    public AgentStepSummary {
        if (!machineIdentifier(stepId) || !machineIdentifier(operation)) {
            throw new IllegalArgumentException("步骤标识或操作标识不合法");
        }
        Objects.requireNonNull(kind, "kind不能为空");
        Objects.requireNonNull(status, "status不能为空");
        dependsOn = dependsOn == null ? java.util.List.of() : java.util.List.copyOf(dependsOn);
        if (dependsOn.stream().anyMatch(dependency -> !machineIdentifier(dependency))) {
            throw new IllegalArgumentException("步骤依赖标识不合法");
        }
        if (status != Status.FAILED && errorCode != null) {
            throw new IllegalArgumentException("仅失败步骤可携带错误码");
        }
        if (status == Status.FAILED && errorCode == null) {
            throw new IllegalArgumentException("失败步骤必须携带错误码");
        }
    }

    static boolean machineIdentifier(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    }

    public enum Kind {
        PLAN, RETRIEVAL, TOOL, GENERATION, VERIFICATION, REFLECTION
    }

    public enum Status {
        PENDING, RUNNING, COMPLETED, SUCCEEDED, FAILED, SKIPPED
    }
}
