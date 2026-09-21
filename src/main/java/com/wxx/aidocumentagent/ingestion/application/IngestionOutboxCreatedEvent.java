package com.wxx.aidocumentagent.ingestion.application;

/** 事务提交后触发一次低延迟投递；定时扫描仍是最终兜底。 */
public record IngestionOutboxCreatedEvent(String eventId) {
}
