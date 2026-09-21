package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxMessageType;

/** 已被数据库租约独占的 outbox 快照；payload 始终是小型版本化消息契约。 */
public record OutboxDispatchEnvelope(
        String eventId,
        OutboxMessageType messageType,
        OutboxDispatchMode dispatchMode,
        long retryDelayMillis,
        Object payload) {
}
