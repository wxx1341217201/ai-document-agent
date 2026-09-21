package com.wxx.aidocumentagent.ingestion.domain;

/** 单个 chunk batch 的状态机。 */
public enum BatchTaskStatus {
    PENDING_DISPATCH,
    QUEUED,
    PROCESSING,
    RETRYING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
