package com.wxx.aidocumentagent.ingestion.messaging;

import java.time.Instant;
import java.util.UUID;

import com.wxx.aidocumentagent.ingestion.domain.IngestionOperation;

/** 文档级协调消息：仅携带标识与控制字段，不携带文件内容。 */
public record DocumentIngestionMessage(
        UUID eventId,
        UUID jobId,
        long documentId,
        long knowledgeBaseId,
        IngestionOperation operation,
        int attempt,
        Instant occurredAt,
        int schemaVersion) {

    public static final int SCHEMA_VERSION = 1;

    public void validate() {
        if (eventId == null || jobId == null || documentId <= 0 || knowledgeBaseId <= 0
                || operation != IngestionOperation.PARSE_AND_SPLIT || attempt < 0 || occurredAt == null
                || schemaVersion != SCHEMA_VERSION) {
            throw new IngestionMessageValidationException("文档协调消息字段不合法");
        }
    }
}
