package com.wxx.aidocumentagent.ingestion.domain;

public enum OutboxEventStatus {
    PENDING,
    DISPATCHING,
    PUBLISHED,
    FAILED
}
