package com.wxx.aidocumentagent.ingestion.application;

import java.util.ArrayList;
import java.util.List;

import com.wxx.aidocumentagent.chunking.application.DocumentChunkingApplicationService;
import com.wxx.aidocumentagent.chunking.application.DocumentChunkingResult;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.parser.DocumentParserRegistry;
import com.wxx.aidocumentagent.document.parser.DocumentSource;
import com.wxx.aidocumentagent.document.parser.DocumentType;
import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.document.storage.DocumentStorage;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.ingestion.messaging.DocumentIngestionMessage;
import com.wxx.aidocumentagent.ingestion.messaging.IngestionMessageValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档级 worker：解析并持久化 chunk 后，只创建可并行的 batch 子任务。 */
@Service
public class DocumentIngestionCoordinator {

    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentBatchTaskRepository batchTaskRepository;
    private final DocumentIngestionOutboxEventRepository outboxEventRepository;
    private final DocumentRepository documentRepository;
    private final DocumentStorage documentStorage;
    private final DocumentParserRegistry parserRegistry;
    private final DocumentChunkingApplicationService chunkingApplicationService;
    private final IngestionProperties properties;

    public DocumentIngestionCoordinator(DocumentIngestionJobRepository jobRepository,
                                        DocumentBatchTaskRepository batchTaskRepository,
                                        DocumentIngestionOutboxEventRepository outboxEventRepository,
                                        DocumentRepository documentRepository,
                                        DocumentStorage documentStorage,
                                        DocumentParserRegistry parserRegistry,
                                        DocumentChunkingApplicationService chunkingApplicationService,
                                        IngestionProperties properties) {
        this.jobRepository = jobRepository;
        this.batchTaskRepository = batchTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.documentRepository = documentRepository;
        this.documentStorage = documentStorage;
        this.parserRegistry = parserRegistry;
        this.chunkingApplicationService = chunkingApplicationService;
        this.properties = properties;
    }

    @Transactional
    public void process(DocumentIngestionMessage message) {
        DocumentIngestionJob job = jobRepository.findLockedByJobIdAndKnowledgeBaseId(message.jobId().toString(),
                message.knowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("摄取任务不存在"));
        validateScope(job, message);
        if (job.getStatus().isTerminal() || job.getStatus() == IngestionJobStatus.PROCESSING) {
            return;
        }
        if (job.getStatus() == IngestionJobStatus.UPLOADED) {
            throw new RetryableIngestionException("任务尚未收到publisher confirm");
        }
        if (!job.beginProcessing(java.time.LocalDateTime.now())) {
            throw new IllegalStateException("文档摄取任务状态不允许开始处理");
        }
        Document document = documentRepository.findByIdAndKnowledgeBaseId(message.documentId(), message.knowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("摄取文档不存在或知识库不匹配"));
        document.markProcessing();

        // 如果进程在 ACK 前中断，重投的同一 job 只会看到 PROCESSING，不会重复替换 chunk 或重复建 batch。
        if (!batchTaskRepository.findByJobIdAndKnowledgeBaseIdOrderByBatchNoAsc(job.getJobId(),
                job.getKnowledgeBaseId()).isEmpty()) {
            return;
        }

        ParsedDocument parsedDocument = parse(document);
        DocumentChunkingResult chunkingResult = chunkingApplicationService.replace(document.getKnowledgeBaseId(),
                document.getId(), parsedDocument);

        // replace() 通过带 clearAutomatically 的批量删除替换 chunk，会清空当前 persistence context。
        // 因此必须重新按知识库边界锁定读取任务与文档；否则 defineBatches() 会修改已脱管实体，
        // 造成 totalBatchCount 未持久化而破坏后续 batch 聚合。
        DocumentIngestionJob persistedJob = jobRepository.findLockedByJobIdAndKnowledgeBaseId(job.getJobId(),
                job.getKnowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("摄取任务不存在"));
        Document persistedDocument = documentRepository.findByIdAndKnowledgeBaseId(document.getId(),
                document.getKnowledgeBaseId()).orElseThrow(() -> new IngestionMessageValidationException("摄取文档不存在或知识库不匹配"));
        createBatchTasks(persistedJob, persistedDocument, chunkingResult.chunks().size());
    }

    private ParsedDocument parse(Document document) {
        DocumentType documentType = DocumentType.fromExtension(document.getExtension())
                .orElseThrow(() -> new IngestionMessageValidationException("文档扩展名不受支持"));
        return parserRegistry.parse(new DocumentSource(documentType, document.getOriginalName(),
                () -> documentStorage.load(document.getStorageKey())));
    }

    private void createBatchTasks(DocumentIngestionJob job, Document document, int chunkCount) {
        if (chunkCount == 0) {
            job.defineBatches(0);
            job.updateBatchSummary(0, 0, 0);
            job.markReady(java.time.LocalDateTime.now());
            document.markReady();
            return;
        }
        List<DocumentBatchTask> tasks = new ArrayList<>();
        for (int chunkFrom = 0, batchNo = 0; chunkFrom < chunkCount;
             chunkFrom += properties.getBatchSize(), batchNo++) {
            int chunkTo = Math.min(chunkCount - 1, chunkFrom + properties.getBatchSize() - 1);
            tasks.add(DocumentBatchTask.create(job, batchNo, chunkFrom, chunkTo));
        }
        job.defineBatches(tasks.size());
        batchTaskRepository.saveAll(tasks);
        List<DocumentIngestionOutboxEvent> events = tasks.stream()
                .map(task -> DocumentIngestionOutboxEvent.batch(job, task, 0, OutboxDispatchMode.PRIMARY, 0))
                .toList();
        outboxEventRepository.saveAll(events);
    }

    private void validateScope(DocumentIngestionJob job, DocumentIngestionMessage message) {
        if (job.getDocumentId() != message.documentId() || job.getKnowledgeBaseId() != message.knowledgeBaseId()
                || job.getOperation() != message.operation()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
    }
}
