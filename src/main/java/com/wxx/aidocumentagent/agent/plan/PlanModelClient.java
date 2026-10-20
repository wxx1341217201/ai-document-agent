package com.wxx.aidocumentagent.agent.plan;

/** 单次模型调用；截止时间和调用次数由 Agent 外层统一约束。 */
@FunctionalInterface
public interface PlanModelClient {
    Reply complete(String system, String user);

    default Reply complete(Phase phase, String system, String user) { return complete(system, user); }
    default Reply complete(Phase phase, String system, String user, java.util.List<String> sourceIds) {
        return complete(phase, system, user);
    }

    enum Phase { PLAN, REPAIR, SYNTHESIZE, VERIFY }

    record Reply(String text, String modelName, Integer promptTokens, Integer completionTokens,
                 Integer totalTokens) { }
}
