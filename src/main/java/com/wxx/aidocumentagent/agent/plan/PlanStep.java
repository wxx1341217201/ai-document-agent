package com.wxx.aidocumentagent.agent.plan;

import java.util.List;
import java.util.Objects;

/** 受控计划定义。状态属于一次执行，不由模型提交。 */
public record PlanStep(String id, PlanAction action, String query, List<String> dependsOn) {
    public PlanStep {
        Objects.requireNonNull(action, "action不能为空");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
