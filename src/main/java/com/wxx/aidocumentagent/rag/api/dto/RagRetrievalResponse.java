package com.wxx.aidocumentagent.rag.api.dto;

/** 只返回调用方需要的检索健康摘要，不泄露下游服务错误正文。 */
public record RagRetrievalResponse(boolean degraded, int candidateCount) {
}
