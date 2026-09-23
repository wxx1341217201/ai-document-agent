package com.wxx.aidocumentagent.ingestion.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.ChunkBatchStage;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionOperation;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.ingestion.messaging.ChunkBatchMessage;
import com.wxx.aidocumentagent.ingestion.messaging.DocumentIngestionMessage;
import com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionFailureHandlerTest {

    private static final long KNOWLEDGE_BASE_ID = 9L;
    private static final long DOCUMENT_ID = 101L;

    @Mock private DocumentIngestionJobRepository jobRepository;
    @Mock private DocumentBatchTaskRepository batchTaskRepository;
    @Mock private DocumentIngestionOutboxEventRepository outboxEventRepository;
    @Mock private DocumentRepository documentRepository;

    private IngestionFailureHandler failureHandler;
    private DocumentIngestionJob job;
    private Document document;

    @BeforeEach
    void setUp() {
        IngestionProperties properties = new IngestionProperties();
        properties.setMaxAttempts(3);
        failureHandler = new IngestionFailureHandler(jobRepository, batchTaskRepository, outboxEventRepository,
                documentRepository, new IngestionFailureClassifier(), properties);
        job = DocumentIngestionJob.create(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        job.markQueued(LocalDateTime.now());
        job.beginProcessing(LocalDateTime.now());
        document = Document.uploaded(KNOWLEDGE_BASE_ID, "guide.txt", "550e8400-e29b-41d4-a716-446655440000.txt",
                "text/plain", "txt", 10L, "a".repeat(64));
        document.markQueued();
        document.markProcessing();
        when(jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(job));
        when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(document));
    }

    @Test
    void 瞬时故障创建有限延迟重试事件且确认原消息即可() {
        IngestionFailureDisposition disposition = failureHandler.handleDocumentFailure(message(),
                new RetryableIngestionException("temporary"));

        assertThat(disposition).isEqualTo(IngestionFailureDisposition.RETRY_SCHEDULED);
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.RETRYING);
        assertThat(job.getAttempt()).isEqualTo(1);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.RETRYING);
        verify(outboxEventRepository).save(any());
    }

    @Test
    void 永久故障直接失败并且不再创建重试事件() {
        IngestionFailureDisposition disposition = failureHandler.handleDocumentFailure(message(),
                new IllegalArgumentException("bad input"));

        assertThat(disposition).isEqualTo(IngestionFailureDisposition.DEAD_LETTER);
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void 任一batch永久失败会持久化聚合计数且绝不将文档标记为READY() {
        job.defineBatches(2);
        DocumentBatchTask task = DocumentBatchTask.create(job, 0, 0, 49);
        task.markQueued(LocalDateTime.now());
        task.beginProcessing(LocalDateTime.now());
        when(batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(task.getBatchId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(task));
        when(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(), KNOWLEDGE_BASE_ID,
                BatchTaskStatus.COMPLETED)).thenReturn(0L);
        when(batchTaskRepository.countByJobIdAndKnowledgeBaseIdAndStatus(job.getJobId(), KNOWLEDGE_BASE_ID,
                BatchTaskStatus.FAILED)).thenReturn(1L);

        IngestionFailureDisposition disposition = failureHandler.handleBatchFailure(batchMessage(task),
                new IllegalArgumentException("invalid batch"));

        assertThat(disposition).isEqualTo(IngestionFailureDisposition.DEAD_LETTER);
        assertThat(task.getStatus()).isEqualTo(BatchTaskStatus.FAILED);
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(job.getCompletedBatchCount()).isZero();
        assertThat(job.getFailedBatchCount()).isEqualTo(1);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void 向量已成功但ES暂时不可用时只重试关键词阶段且不使任务READY() {
        job.defineBatches(1);
        DocumentBatchTask task = DocumentBatchTask.create(job, 0, 0, 49);
        task.markQueued(LocalDateTime.now());
        task.beginProcessing(LocalDateTime.now());
        task.advanceToKeywordIndex();
        task.markQueued(LocalDateTime.now());
        task.beginProcessing(LocalDateTime.now());
        when(batchTaskRepository.findLockedByBatchIdAndKnowledgeBaseId(task.getBatchId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(task));

        IngestionFailureDisposition disposition = failureHandler.handleBatchFailure(batchMessage(task),
                new KeywordIndexException(KeywordIndexErrorCode.ELASTICSEARCH_UNAVAILABLE));

        assertThat(disposition).isEqualTo(IngestionFailureDisposition.RETRY_SCHEDULED);
        assertThat(task.getStage()).isEqualTo(ChunkBatchStage.KEYWORD_INDEX);
        assertThat(task.getStatus()).isEqualTo(BatchTaskStatus.RETRYING);
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.RETRYING);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.RETRYING);
        ArgumentCaptor<DocumentIngestionOutboxEvent> eventCaptor = ArgumentCaptor.forClass(DocumentIngestionOutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getBatchStage()).isEqualTo(ChunkBatchStage.KEYWORD_INDEX);
    }

    private DocumentIngestionMessage message() {
        return new DocumentIngestionMessage(UUID.randomUUID(), UUID.fromString(job.getJobId()), DOCUMENT_ID,
                KNOWLEDGE_BASE_ID, IngestionOperation.PARSE_AND_SPLIT, 0, Instant.now(), 1);
    }

    private ChunkBatchMessage batchMessage(DocumentBatchTask task) {
        return new ChunkBatchMessage(UUID.randomUUID(), UUID.fromString(job.getJobId()), UUID.fromString(task.getBatchId()),
                DOCUMENT_ID, KNOWLEDGE_BASE_ID, task.getChunkFrom(), task.getChunkTo(), task.getStage(),
                0, 1);
    }
}
