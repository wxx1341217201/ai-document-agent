package com.wxx.aidocumentagent.ingestion.benchmark;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wxx.aidocumentagent.ingestion.application.*;
import com.wxx.aidocumentagent.ingestion.domain.*;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.*;
import com.wxx.aidocumentagent.ingestion.messaging.DocumentIngestionMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SerialBenchmarkIngestionTest {
    @Test void roundedAvailableAtCanBecomeDispatchableWithoutDuplicateProcessing() {
        var jobs = mock(DocumentIngestionJobRepository.class);
        var batches = mock(DocumentBatchTaskRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var lifecycle = mock(IngestionOutboxLifecycleService.class);
        var coordinator = mock(DocumentIngestionCoordinator.class);
        var worker = mock(ChunkBatchIndexingApplicationService.class);
        var job = mock(DocumentIngestionJob.class);
        UUID jobId = UUID.randomUUID(), eventId = UUID.randomUUID();
        when(job.getJobId()).thenReturn(jobId.toString());
        when(job.getStatus()).thenReturn(IngestionJobStatus.READY);
        when(jobs.findByDocumentIdAndKnowledgeBaseId(1, 2)).thenReturn(Optional.of(job));
        when(jobs.findByJobIdAndKnowledgeBaseId(jobId.toString(), 2)).thenReturn(Optional.of(job));
        when(batches.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(jobId.toString(), 2)).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(jobId.toString()))).thenReturn(eventId.toString());
        var message = new DocumentIngestionMessage(eventId, jobId, 1, 2, IngestionOperation.PARSE_AND_SPLIT,
                0, Instant.now(), DocumentIngestionMessage.SCHEMA_VERSION);
        var envelope = new OutboxDispatchEnvelope(eventId.toString(), OutboxMessageType.DOCUMENT_COORDINATOR,
                OutboxDispatchMode.PRIMARY, 0, message);
        when(lifecycle.claim(eventId.toString())).thenReturn(Optional.empty(), Optional.of(envelope));
        new SerialBenchmarkIngestion(jobs, batches, jdbc, lifecycle, coordinator, worker).process(1, 2);
        verify(lifecycle, times(2)).claim(eventId.toString());
        verify(lifecycle).markPublished(eventId.toString());
        verify(coordinator).process(message);
        verifyNoInteractions(worker);
    }
    @Test void interruptedWaitCannotAdvanceUnclaimedTask() {
        var jobs = mock(DocumentIngestionJobRepository.class);
        var batches = mock(DocumentBatchTaskRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var lifecycle = mock(IngestionOutboxLifecycleService.class);
        var coordinator = mock(DocumentIngestionCoordinator.class);
        var worker = mock(ChunkBatchIndexingApplicationService.class);
        var job = mock(DocumentIngestionJob.class);
        when(job.getJobId()).thenReturn("job");
        when(jobs.findByDocumentIdAndKnowledgeBaseId(1, 2)).thenReturn(Optional.of(job));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("job"))).thenReturn("event");
        when(lifecycle.claim("event")).thenReturn(Optional.empty());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new SerialBenchmarkIngestion(jobs, batches, jdbc, lifecycle, coordinator, worker).process(1, 2))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Inline dispatch interrupted");
            verify(lifecycle, never()).markPublished(anyString());
            verifyNoInteractions(coordinator, worker);
        } finally { Thread.interrupted(); }
    }
}
