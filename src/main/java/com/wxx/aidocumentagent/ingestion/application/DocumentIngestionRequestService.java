package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJob;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEvent;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 上传事务内创建唯一摄取任务和 outbox 事件，不在 HTTP 请求中解析文件。 */
@Service
public class DocumentIngestionRequestService {

    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentIngestionOutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentIngestionRequestService(DocumentIngestionJobRepository jobRepository,
                                           DocumentIngestionOutboxEventRepository outboxEventRepository,
                                           ApplicationEventPublisher eventPublisher) {
        this.jobRepository = jobRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requestInitialIngestion(Document document) {
        if (jobRepository.findByDocumentIdAndKnowledgeBaseId(document.getId(), document.getKnowledgeBaseId()).isPresent()) {
            return;
        }
        DocumentIngestionJob job = jobRepository.save(DocumentIngestionJob.create(document.getKnowledgeBaseId(),
                document.getId()));
        DocumentIngestionOutboxEvent event = outboxEventRepository.save(
                DocumentIngestionOutboxEvent.document(job, 0, OutboxDispatchMode.PRIMARY, 0));
        eventPublisher.publishEvent(new IngestionOutboxCreatedEvent(event.getEventId()));
    }
}
