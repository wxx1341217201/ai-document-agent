package com.wxx.aidocumentagent.rag.application;

/** ChatClient 的可审计显式输出；未知 token 以 null 表示，不能伪造为 0。 */
public record RagChatCompletion(
        String answer,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {

    public RagChatCompletion {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("模型回答不能为空");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        validateTokenCount(promptTokens, "promptTokens");
        validateTokenCount(completionTokens, "completionTokens");
        validateTokenCount(totalTokens, "totalTokens");
    }

    private static void validateTokenCount(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }
}
