package com.wxx.aidocumentagent.ingestion.domain;

/** 文档摄取任务的持久化状态机。 */
public enum IngestionJobStatus {
    UPLOADED,
    QUEUED,
    PROCESSING,
    RETRYING,
    READY,
    FAILED;

    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }
}
