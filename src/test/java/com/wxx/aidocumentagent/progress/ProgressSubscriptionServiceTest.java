package com.wxx.aidocumentagent.progress;

import java.util.Optional;

import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProgressSubscriptionServiceTest {
    private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentIngestionJobRepository jobs = mock(DocumentIngestionJobRepository.class);

    @Test
    void authorizationPrecedesLookupAndWrongKnowledgeBaseIsRejected() {
        ProgressSubscriptionService service = new ProgressSubscriptionService(
                (subscriber, request) -> subscriber.sharedTokenAuthenticated(), executions, documents, jobs);
        String id = "12345678-1234-1234-1234-123456789abc";
        ProgressSubscriptionRequest request = new ProgressSubscriptionRequest(ProgressResource.EXECUTION, 8, id, null);
        assertThatThrownBy(() -> service.resolve(new ProgressSubscriber(null, false), request))
                .isInstanceOfSatisfying(ProgressSubscriptionException.class,
                        error -> assertThat(error.code()).isEqualTo("FORBIDDEN"));
        verifyNoInteractions(executions, documents, jobs);

        assertThatThrownBy(() -> service.resolve(new ProgressSubscriber(null, true), request))
                .isInstanceOfSatisfying(ProgressSubscriptionException.class,
                        error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
        verify(executions).findByExecutionIdAndKnowledgeBaseId(id, 8);
    }

    @Test
    void documentSubscriptionResolvesOnlyScopedJob() {
        ProgressSubscriptionService service = new ProgressSubscriptionService((subscriber, request) -> true,
                executions, documents, jobs);
        Document document = mock(Document.class);
        DocumentIngestionJob job = DocumentIngestionJob.create(7, 12);
        when(documents.findByIdAndKnowledgeBaseId(12L, 7L)).thenReturn(Optional.of(document));
        when(jobs.findByDocumentIdAndKnowledgeBaseId(12L, 7L)).thenReturn(Optional.of(job));
        var request = new ProgressSubscriptionRequest(ProgressResource.DOCUMENT, 7, null, 12L);
        assertThat(service.resolve(new ProgressSubscriber(null, true), request))
                .isEqualTo(ProgressStream.document(7, job.getJobId(), 12));
        verify(jobs).findByDocumentIdAndKnowledgeBaseId(12, 7);

        var wrongScope = new ProgressSubscriptionRequest(ProgressResource.DOCUMENT, 8, null, 12L);
        assertThatThrownBy(() -> service.resolve(new ProgressSubscriber(null, true), wrongScope))
                .isInstanceOf(ProgressSubscriptionException.class);
        verify(documents).findByIdAndKnowledgeBaseId(12L, 8L);
        verify(jobs, never()).findByDocumentIdAndKnowledgeBaseId(12L, 8L);
    }
}
