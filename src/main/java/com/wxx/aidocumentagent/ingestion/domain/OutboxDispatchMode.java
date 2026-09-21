package com.wxx.aidocumentagent.ingestion.domain;

/** PRIMARY 直接进入工作队列；RETRY 先进入 TTL 重试队列。 */
public enum OutboxDispatchMode {
    PRIMARY,
    RETRY
}
