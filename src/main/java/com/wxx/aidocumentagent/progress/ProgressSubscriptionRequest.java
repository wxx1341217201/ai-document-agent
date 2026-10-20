package com.wxx.aidocumentagent.progress;

public record ProgressSubscriptionRequest(ProgressResource resource, long knowledgeBaseId,
                                          String executionId, Long documentId) { }
