package com.wxx.aidocumentagent.ingestion.application;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.ingestion.messaging.ChunkBatchMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkBatchTaskLifecycleServiceTest {

    private static final long KNOWLEDGE_BASE_ID = 9L;
    private static final long DOCUMENT_ID = 101L;

    @Mock private DocumentBatchTaskRepository batchTaskRepository;
    @Mock private DocumentIngestionJobRepository jobRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentIngestionOutboxEventRepository outboxEventRepository;

    private ChunkBatchTaskLifecycleService lifecycleService;
    private DocumentIngestionJob job;
    private Document document;
    private DocumentBatchTask task;

    @BeforeEach
    void setUp() {
        lifecycleService = new ChunkBatchTaskLifecycleService(batchTaskRepository, jobRepository, documentRepository,
                outboxEventRepository);
        job = DocumentIngestionJob.create(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        job.markQueued(LocalDateTime.now());
        job.beginProcessing(LocalDateTime.now());
        job.defineBatches(1);
        document = Document.uploaded(KNOWLEDGE_BASE_ID, "manual.pdf", "550e8400-e29b-41d4-a716-446655440000.pdf",
                "application/pdf", "pdf", 1L, "a".repeat(64));
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        document.markQueued();
        document.markProcessing();
        task = DocumentBatchTask.create(job, 0, 0, 49);
        task.markQueued(LocalDateTime.now());

        when(batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(task.getBatchId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(task));
        when(jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(job));
        when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(document));
    }

    @Test
    void 向量成功只调度关键词阶段且关键词成功后才允许聚合READY() {
        BatchWork vectorWork = lifecycleService.claim(message(ChunkBatchStage.VECTOR_INDEX)).orElseThrow();

        lifecycleService.complete(vectorWork);

        assertThat(task.getStage()).isEqualTo(ChunkBatchStage.KEYWORD_INDEX);
        assertThat(task.getStatus()).isEqualTo(BatchTaskStatus.PENDING_DISPATCH);
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.PROCESSING);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(batchTaskRepository, never()).countByJobIdAndKnowledgeBaseIdAndStatus(any(), any(Long.class), any());
        ArgumentCaptor<DocumentIngestionOutboxEvent> eventCaptor = ArgumentCaptor.forClass(DocumentIngestionOutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getBatchId()).isEqualTo(task.getBatchId());
        assertThat(eventCaptor.getValue().getBatchStage()).isEqualTo(ChunkBatchStage.KEYWORD_INDEX);

        // 旧 vector 消息即使在切换后重复投递，也不会把 task 退回或重新索引关键词阶段。
        assertThat(lifecycleService.claim(message(ChunkBatchStage.VECTOR_INDEX))).isEmpty();
        task.markQueued(LocalDateTime.now());
        BatchWork keywordWork = lifecycleService.claim(message(ChunkBatchStage.KEYWORD_INDEX)).orElseThrow();
        when(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(), KNOWLEDGE_BASE_ID,
                BatchTaskStatus.COMPLETED)).thenReturn(1L);
        when(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(), KNOWLEDGE_BASE_ID,
                BatchTaskStatus.FAILED)).thenReturn(0L);

        lifecycleService.complete(keywordWork);

        assertThat(task.getStatus()).isEqualTo(BatchTaskStatus.COMPLETED);
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.READY);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
    }

    private ChunkBatchMessage message(ChunkBatchStage stage) {
        return new ChunkBatchMessage(UUID.randomUUID(), UUID.fromString(job.getJobId()), UUID.fromString(task.getBatchId()),
                DOCUMENT_ID, KNOWLEDGE_BASE_ID, task.getChunkFrom(), task.getChunkTo(), stage, 0,
                ChunkBatchMessage.SCHEMA_VERSION);
    }
}
