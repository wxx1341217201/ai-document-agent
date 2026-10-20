package com.wxx.aidocumentagent.agent;

import java.util.Objects;

/** 仅包含用户输入或已完成的助手回答，不存放模型中间推理。 */
public record AgentMemoryMessage(long sequenceNo, Role role, String content) {

    public AgentMemoryMessage {
        if (sequenceNo <= 0L) {
            throw new IllegalArgumentException("sequenceNo必须大于0");
        }
        Objects.requireNonNull(role, "role不能为空");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content不能为空");
        }
    }

    public enum Role {
        USER, ASSISTANT
    }
}
