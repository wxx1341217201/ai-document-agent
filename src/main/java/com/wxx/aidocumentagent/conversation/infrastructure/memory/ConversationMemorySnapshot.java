package com.wxx.aidocumentagent.conversation.infrastructure.memory;

import java.util.List;

/** 带数据库消息序号水位的缓存快照；水位不匹配时必须回源，避免并发提交造成陈旧上下文。 */
public record ConversationMemorySnapshot(long lastMessageSequence, List<ConversationMemoryEntry> messages) {

    public ConversationMemorySnapshot {
        if (lastMessageSequence < 0L) {
            throw new IllegalArgumentException("lastMessageSequence不能为负数");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        long previous = 0L;
        for (ConversationMemoryEntry message : messages) {
            if (message.sequenceNo() <= previous || message.sequenceNo() > lastMessageSequence) {
                throw new IllegalArgumentException("缓存消息顺序不合法");
            }
            previous = message.sequenceNo();
        }
    }
}
