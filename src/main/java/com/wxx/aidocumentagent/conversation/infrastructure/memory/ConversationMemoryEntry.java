package com.wxx.aidocumentagent.conversation.infrastructure.memory;

import java.util.Objects;

import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;

/** Redis 中的最小上下文单元，不保存引用、模型原始响应或任何隐藏推理。 */
public record ConversationMemoryEntry(long sequenceNo, ConversationMessageRole role, String content) {

    public ConversationMemoryEntry {
        if (sequenceNo <= 0L) {
            throw new IllegalArgumentException("sequenceNo必须大于0");
        }
        role = Objects.requireNonNull(role, "role不能为空");
        if (role != ConversationMessageRole.USER && role != ConversationMessageRole.ASSISTANT) {
            throw new IllegalArgumentException("短期上下文只允许USER或ASSISTANT消息");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content不能为空");
        }
    }
}
