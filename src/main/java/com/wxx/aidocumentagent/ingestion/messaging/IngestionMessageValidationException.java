package com.wxx.aidocumentagent.ingestion.messaging;

/** 无法安全归属到某个任务的消息会直接拒绝并进入 DLQ。 */
public class IngestionMessageValidationException extends RuntimeException {

    public IngestionMessageValidationException(String message) {
        super(message);
    }

    public IngestionMessageValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
