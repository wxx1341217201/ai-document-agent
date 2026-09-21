package com.wxx.aidocumentagent.ingestion.application;

/** Publisher confirm NACK、超时或不可用时抛出，由 outbox 有限重试处理。 */
public class IngestionPublishException extends RuntimeException {

    public IngestionPublishException(String message) {
        super(message);
    }

    public IngestionPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
