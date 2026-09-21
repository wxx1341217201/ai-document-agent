package com.wxx.aidocumentagent.ingestion.application;

/** 明确标记为可有限重试的处理故障。 */
public class RetryableIngestionException extends RuntimeException {

    public RetryableIngestionException(String message) {
        super(message);
    }

    public RetryableIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
