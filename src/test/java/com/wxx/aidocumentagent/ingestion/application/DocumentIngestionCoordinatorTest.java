package com.wxx.aidocumentagent.ingestion.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import com.wxx.aidocumentagent.chunking.TextChunk;
import com.wxx.aidocumentagent.chunking.application.DocumentChunkingApplicationService;
import com.wxx.aidocumentagent.chunking.application.DocumentChunkingResult;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.parser.DocumentParser;
import com.wxx.aidocumentagent.document.parser.DocumentParserRegistry;
import com.wxx.aidocumentagent.document.parser.DocumentSource;
import com.wxx.aidocumentagent.document.parser.DocumentType;
import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.document.storage.DocumentStorage;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.domain.IngestionOperation;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.ingestion.messaging.DocumentIngestionMessage;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.vector.VectorIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionCoordinatorTest {

    private static final long KNOWLEDGE_BASE_ID = 9L;
    private static final long DOCUMENT_ID = 101L;

    @Mock private DocumentIngestionJobRepository jobRepository;
    @Mock private DocumentBatchTaskRepository batchTaskRepository;
    @Mock private DocumentIngestionOutboxEventRepository outboxEventRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentStorage documentStorage;
    @Mock private DocumentChunkingApplicationService chunkingApplicationService;
    @Mock private ObjectProvider<VectorIndex> vectorIndexProvider;
    @Mock private VectorIndex vectorIndex;
    @Mock private ObjectProvider<KeywordIndex> keywordIndexProvider;
    @Mock private KeywordIndex keywordIndex;

    private DocumentIngestionCoordinator coordinator;
    private DocumentIngestionJob job;
    private Document document;

    @BeforeEach
    void setUp() {
        IngestionProperties properties = new IngestionProperties();
        properties.setBatchSize(50);
        ParsedDocument parsed = new ParsedDocument("正文", List.of(), Map.of(), List.of(), List.of());
        DocumentParser parser = new DocumentParser() {
            @Override
            public boolean supports(DocumentType type) {
                return true;
            }

            @Override
            public ParsedDocument parse(DocumentSource source) {
                return parsed;
            }
        };
        coordinator = new DocumentIngestionCoordinator(jobRepository, batchTaskRepository, outboxEventRepository,
                documentRepository, documentStorage, new DocumentParserRegistry(List.of(parser)),
                chunkingApplicationService, properties, vectorIndexProvider, keywordIndexProvider);
        job = DocumentIngestionJob.create(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        job.markQueued(LocalDateTime.now());
        document = Document.uploaded(KNOWLEDGE_BASE_ID, "long.txt", "550e8400-e29b-41d4-a716-446655440000.txt",
                "text/plain", "txt", 100L, "a".repeat(64));
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
    }

    @Test
    void 长文档按配置拆成可并行batch并为每个batch写出outbox事件() {
        when(jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(job));
        when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(document));
        when(batchTaskRepository.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(List.of());
        List<TextChunk> chunks = IntStream.range(0, 120)
                .mapToObj(index -> new TextChunk(index, "chunk-" + index, 1, null, null, null, Map.of()))
                .toList();
        when(chunkingApplicationService.replace(eq(KNOWLEDGE_BASE_ID), eq(DOCUMENT_ID), any(ParsedDocument.class)))
                .thenReturn(new DocumentChunkingResult(KNOWLEDGE_BASE_ID, DOCUMENT_ID, "paragraph", 0, chunks));

        coordinator.process(message());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Iterable<DocumentBatchTask>> taskCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Iterable.class);
        verify(batchTaskRepository).saveAll(taskCaptor.capture());
        List<DocumentBatchTask> tasks = ((List<DocumentBatchTask>) taskCaptor.getValue());
        assertThat(tasks).hasSize(3);
        assertThat(tasks).extracting(DocumentBatchTask::getChunkFrom).containsExactly(0, 50, 100);
        assertThat(tasks).extracting(DocumentBatchTask::getChunkTo).containsExactly(49, 99, 119);
        assertThat(job.getTotalBatchCount()).isEqualTo(3);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Iterable<DocumentIngestionOutboxEvent>> eventCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Iterable.class);
        verify(outboxEventRepository).saveAll(eventCaptor.capture());
        assertThat((List<DocumentIngestionOutboxEvent>) eventCaptor.getValue()).hasSize(3);
    }

    @Test
    void 切分清空持久化上下文后重新读取任务以持久化batch总数() {
        DocumentIngestionJob reloadedJob = DocumentIngestionJob.create(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        ReflectionTestUtils.setField(reloadedJob, "jobId", job.getJobId());
        reloadedJob.markQueued(LocalDateTime.now());
        reloadedJob.beginProcessing(LocalDateTime.now());
        Document reloadedDocument = Document.uploaded(KNOWLEDGE_BASE_ID, "long.txt",
                "550e8400-e29b-41d4-a716-446655440001.txt", "text/plain", "txt", 100L, "b".repeat(64));
        ReflectionTestUtils.setField(reloadedDocument, "id", DOCUMENT_ID);

        when(jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(job), Optional.of(reloadedJob));
        when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(document), Optional.of(reloadedDocument));
        when(batchTaskRepository.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(List.of());
        List<TextChunk> chunks = IntStream.range(0, 120)
                .mapToObj(index -> new TextChunk(index, "chunk-" + index, 1, null, null, null, Map.of()))
                .toList();
        when(chunkingApplicationService.replace(eq(KNOWLEDGE_BASE_ID), eq(DOCUMENT_ID), any(ParsedDocument.class)))
                .thenReturn(new DocumentChunkingResult(KNOWLEDGE_BASE_ID, DOCUMENT_ID, "paragraph", 0, chunks));

        coordinator.process(message());

        assertThat(job.getTotalBatchCount()).isZero();
        assertThat(reloadedJob.getTotalBatchCount()).isEqualTo(3);
    }

    @Test
    void 重复文档消息在已处理中的job上不会再次切分或创建batch() {
        job.beginProcessing(LocalDateTime.now());
        when(jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(job));

        coordinator.process(message());

        verify(chunkingApplicationService, never()).replace(anyLong(), anyLong(), any());
        verify(batchTaskRepository, never()).saveAll(any());
        verify(outboxEventRepository, never()).saveAll(any());
    }

    @Test
    void 重新切分前按知识库和文档边界清除旧向量() {
        when(jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(job), Optional.of(job));
        when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(document), Optional.of(document));
        when(batchTaskRepository.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(List.of());
        when(vectorIndexProvider.getIfAvailable()).thenReturn(vectorIndex);
        List<TextChunk> chunks = List.of(new TextChunk(0, "chunk-0", 1, null, null, null, Map.of()));
        when(chunkingApplicationService.replace(eq(KNOWLEDGE_BASE_ID), eq(DOCUMENT_ID), any(ParsedDocument.class)))
                .thenReturn(new DocumentChunkingResult(KNOWLEDGE_BASE_ID, DOCUMENT_ID, "paragraph", 0, chunks));

        coordinator.process(message());

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(vectorIndex, chunkingApplicationService);
        inOrder.verify(vectorIndex).deleteByDocument(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        inOrder.verify(chunkingApplicationService).replace(eq(KNOWLEDGE_BASE_ID), eq(DOCUMENT_ID), any(ParsedDocument.class));
    }

    @Test
    void 重新切分前按知识库和文档边界清除旧关键词索引() {
        when(jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(job), Optional.of(job));
        when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(document), Optional.of(document));
        when(batchTaskRepository.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(job.getJobId(), KNOWLEDGE_BASE_ID))
                .thenReturn(List.of());
        when(vectorIndexProvider.getIfAvailable()).thenReturn(vectorIndex);
        when(keywordIndexProvider.getIfAvailable()).thenReturn(keywordIndex);
        List<TextChunk> chunks = List.of(new TextChunk(0, "chunk-0", 1, null, null, null, Map.of()));
        when(chunkingApplicationService.replace(eq(KNOWLEDGE_BASE_ID), eq(DOCUMENT_ID), any(ParsedDocument.class)))
                .thenReturn(new DocumentChunkingResult(KNOWLEDGE_BASE_ID, DOCUMENT_ID, "paragraph", 0, chunks));

        coordinator.process(message());

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(vectorIndex, keywordIndex, chunkingApplicationService);
        inOrder.verify(vectorIndex).deleteByDocument(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        inOrder.verify(keywordIndex).deleteByDocument(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        inOrder.verify(chunkingApplicationService).replace(eq(KNOWLEDGE_BASE_ID), eq(DOCUMENT_ID), any(ParsedDocument.class));
    }

    private DocumentIngestionMessage message() {
        return new DocumentIngestionMessage(UUID.randomUUID(), UUID.fromString(job.getJobId()), DOCUMENT_ID,
                KNOWLEDGE_BASE_ID, IngestionOperation.PARSE_AND_SPLIT, 0, java.time.Instant.now(), 1);
    }
}
