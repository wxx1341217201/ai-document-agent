package com.wxx.aidocumentagent.progress;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.execution.ExecutionProperties;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.ToolInvocationRepository;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.application.IngestionFailureClassifier;
import com.wxx.aidocumentagent.ingestion.application.IngestionFailureDisposition;
import com.wxx.aidocumentagent.ingestion.application.IngestionFailureHandler;
import com.wxx.aidocumentagent.ingestion.domain.IngestionOperation;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.ingestion.messaging.DocumentIngestionMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProgressTerminalPublishingTest {
    @Test
    void agentSuccessAndFailureEmitTerminalTypes() {
        JsonMapper mapper = new JsonMapper();
        AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
        AgentStepExecutionRepository steps = mock(AgentStepExecutionRepository.class);
        ProgressEmitter emitter = mock(ProgressEmitter.class);
        ExecutionStore store = new ExecutionStore(executions, steps, mock(ToolInvocationRepository.class),
                new ExecutionProperties(), mapper, new RetrievalFirstProperties(), new ReActProperties(),
                new PlanProperties());
        store.setProgressEmitter(emitter);

        AgentExecution succeeded = AgentExecution.create("success", "hash", 7, null, AgentMode.REACT,
                "question", mapper.valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        String successToken = succeeded.claim(LocalDateTime.now(), LocalDateTime.now().plusMinutes(2), false);
        succeeded.checkpoint(mapper.valueToTree(Map.of("answer", "done")));
        when(executions.findLocked(succeeded.getExecutionId(), 7)).thenReturn(Optional.of(succeeded));
        store.complete(succeeded.getExecutionId(), 7, successToken);
        verify(emitter).afterCommit(eq(ProgressStream.agent(7, succeeded.getExecutionId())),
                eq(ProgressEventType.EXECUTION_COMPLETED), any(ProgressPayload.class));

        AgentExecution failed = AgentExecution.create("failed", "hash", 7, null, AgentMode.REACT,
                "question", mapper.valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        String failureToken = failed.claim(LocalDateTime.now(), LocalDateTime.now().plusMinutes(2), false);
        when(executions.findLocked(failed.getExecutionId(), 7)).thenReturn(Optional.of(failed));
        when(steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(failed.getExecutionId(), 7, "agent_run"))
                .thenReturn(Optional.of(AgentStepExecution.agentRun(failed.getExecutionId(), 7)));
        store.fail(failed.getExecutionId(), 7, failureToken, "EXECUTION_FAILED", false);
        ArgumentCaptor<ProgressPayload> captured = ArgumentCaptor.forClass(ProgressPayload.class);
        verify(emitter).afterCommit(eq(ProgressStream.agent(7, failed.getExecutionId())),
                eq(ProgressEventType.EXECUTION_FAILED), captured.capture());
        assertThat(captured.getValue().errorCode()).isEqualTo("EXECUTION_FAILED");
        assertThat(captured.getValue().summary()).doesNotContain("question");
    }

    @Test
    void documentFailureEmitsSafeTerminalCodeAndRedeliveryDoesNotDuplicate() {
        DocumentIngestionJobRepository jobs = mock(DocumentIngestionJobRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        ProgressEmitter emitter = mock(ProgressEmitter.class);
        DocumentIngestionJob job = DocumentIngestionJob.create(7, 12);
        job.markQueued(LocalDateTime.now());
        Document document = Document.uploaded(7L, "report.pdf", "stored.pdf", "application/pdf", "pdf", 1L,
                "a".repeat(64));
        ReflectionTestUtils.setField(document, "id", 12L);
        when(jobs.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), 7)).thenReturn(Optional.of(job));
        when(documents.findByIdAndKnowledgeBaseId(12L, 7L)).thenReturn(Optional.of(document));
        IngestionFailureHandler handler = new IngestionFailureHandler(jobs, mock(DocumentBatchTaskRepository.class),
                mock(DocumentIngestionOutboxEventRepository.class), documents, new IngestionFailureClassifier(),
                new IngestionProperties());
        handler.setProgressEmitter(emitter);
        DocumentIngestionMessage message = new DocumentIngestionMessage(UUID.randomUUID(), UUID.fromString(job.getJobId()),
                12, 7, IngestionOperation.PARSE_AND_SPLIT, 0, Instant.now(), 1);
        assertThat(handler.handleDocumentFailure(message, new RuntimeException("internal stack and secret")))
                .isEqualTo(IngestionFailureDisposition.DEAD_LETTER);
        assertThat(handler.handleDocumentFailure(message, new RuntimeException("redelivery")))
                .isEqualTo(IngestionFailureDisposition.IGNORE);
        ArgumentCaptor<ProgressPayload> captured = ArgumentCaptor.forClass(ProgressPayload.class);
        verify(emitter, times(1)).afterCommit(eq(ProgressStream.document(7, job.getJobId(), 12)),
                eq(ProgressEventType.DOCUMENT_FAILED), captured.capture());
        assertThat(captured.getValue().errorCode()).isEqualTo("INGESTION_PROCESSING_FAILURE");
        assertThat(captured.getValue().summary()).doesNotContain("secret", "stack");
    }

}
