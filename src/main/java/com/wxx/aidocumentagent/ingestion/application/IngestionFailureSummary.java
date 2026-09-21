package com.wxx.aidocumentagent.ingestion.application;

/** 可持久化的错误分类；message 永远是稳定摘要而非异常原文或模型思维链。 */
public record IngestionFailureSummary(String code, String message, boolean retryable) {
}
