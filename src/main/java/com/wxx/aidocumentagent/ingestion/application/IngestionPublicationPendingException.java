package com.wxx.aidocumentagent.ingestion.application;

/** Broker delivery raced the database publisher-confirm commit; no business processing has begun. */
public class IngestionPublicationPendingException extends RetryableIngestionException {
    public IngestionPublicationPendingException(String message) { super(message); }
}
