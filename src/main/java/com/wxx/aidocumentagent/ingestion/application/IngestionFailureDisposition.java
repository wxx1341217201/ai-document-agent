package com.wxx.aidocumentagent.ingestion.application;

/** RETRY_SCHEDULED 时监听器正常返回并确认原消息；其余情况由 RabbitMQ 路由到 DLQ。 */
public enum IngestionFailureDisposition {
    RETRY_SCHEDULED,
    DEAD_LETTER,
    IGNORE
}
