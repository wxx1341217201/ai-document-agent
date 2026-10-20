package com.wxx.aidocumentagent.agent;

/** 未知用量以 null 表示，调用方不能用 0 代替未知的服务商数据。 */
public record AgentUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         Integer modelCalls, Integer toolCalls) {

    public AgentUsage {
        nonNegative(promptTokens, "promptTokens");
        nonNegative(completionTokens, "completionTokens");
        nonNegative(totalTokens, "totalTokens");
        nonNegative(modelCalls, "modelCalls");
        nonNegative(toolCalls, "toolCalls");
    }

    public static AgentUsage unknown() {
        return new AgentUsage(null, null, null, null, null);
    }

    private static void nonNegative(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }
}
