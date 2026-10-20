package com.wxx.aidocumentagent.progress;

import java.util.UUID;

import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Authorization precedes every scoped lookup so a denial cannot disclose resource existence. */
@Service
@ConditionalOnProperty(prefix = "app.progress", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProgressSubscriptionService {
    private final ProgressSubscriptionAuthorizer authorizer;
    private final AgentExecutionRepository executions;
    private final DocumentRepository documents;
    private final DocumentIngestionJobRepository jobs;

    public ProgressSubscriptionService(ProgressSubscriptionAuthorizer authorizer,
                                       AgentExecutionRepository executions, DocumentRepository documents,
                                       DocumentIngestionJobRepository jobs) {
        this.authorizer = authorizer;
        this.executions = executions;
        this.documents = documents;
        this.jobs = jobs;
    }

    public ProgressStream resolve(ProgressSubscriber subscriber, ProgressSubscriptionRequest request) {
        if (request == null || request.resource() == null || request.knowledgeBaseId() <= 0) {
            throw new ProgressSubscriptionException("BAD_REQUEST");
        }
        if (!authorizer.maySubscribe(subscriber, request)) {
            throw new ProgressSubscriptionException("FORBIDDEN");
        }
        if (request.resource() == ProgressResource.EXECUTION) {
            if (request.documentId() != null || !uuid(request.executionId())) {
                throw new ProgressSubscriptionException("BAD_REQUEST");
            }
            var execution = executions.findByExecutionIdAndKnowledgeBaseId(request.executionId(),
                    request.knowledgeBaseId()).orElseThrow(() -> new ProgressSubscriptionException("NOT_FOUND"));
            return ProgressStream.agent(execution.getKnowledgeBaseId(), execution.getExecutionId());
        }
        if (request.executionId() != null || request.documentId() == null || request.documentId() <= 0) {
            throw new ProgressSubscriptionException("BAD_REQUEST");
        }
        long documentId = request.documentId();
        if (documents.findByIdAndKnowledgeBaseId(documentId, request.knowledgeBaseId()).isEmpty()) {
            throw new ProgressSubscriptionException("NOT_FOUND");
        }
        var job = jobs.findByDocumentIdAndKnowledgeBaseId(documentId, request.knowledgeBaseId())
                .orElseThrow(() -> new ProgressSubscriptionException("NOT_FOUND"));
        return ProgressStream.document(job.getKnowledgeBaseId(), job.getJobId(), job.getDocumentId());
    }

    private boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
