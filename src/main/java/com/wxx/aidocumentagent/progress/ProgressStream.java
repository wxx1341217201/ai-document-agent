package com.wxx.aidocumentagent.progress;

import java.util.Objects;
import java.util.UUID;

/** Document executionId is the ingestion job UUID; Agent executionId is the durable run UUID. */
public record ProgressStream(ProgressResource resource, long knowledgeBaseId, String executionId,
                             Long documentId) {
    public ProgressStream {
        Objects.requireNonNull(resource);
        if (knowledgeBaseId <= 0) throw new IllegalArgumentException("knowledgeBaseId must be positive");
        UUID.fromString(executionId);
        if (resource == ProgressResource.DOCUMENT && (documentId == null || documentId <= 0)) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        if (resource == ProgressResource.EXECUTION && documentId != null) {
            throw new IllegalArgumentException("Agent stream cannot have documentId");
        }
    }

    public static ProgressStream agent(long knowledgeBaseId, String executionId) {
        return new ProgressStream(ProgressResource.EXECUTION, knowledgeBaseId, executionId, null);
    }

    public static ProgressStream document(long knowledgeBaseId, String jobId, long documentId) {
        return new ProgressStream(ProgressResource.DOCUMENT, knowledgeBaseId, jobId, documentId);
    }
}
