package com.wxx.aidocumentagent.document.domain;

/**
 * 已上传文档的生命周期状态。
 */
public enum DocumentStatus {
    UPLOADED,
    QUEUED,
    PROCESSING,
    RETRYING,
    READY,
    FAILED,
    DELETED
}
