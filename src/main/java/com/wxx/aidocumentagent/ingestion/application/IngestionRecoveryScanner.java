package com.wxx.aidocumentagent.ingestion.application;

import java.time.LocalDateTime;

import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.domain.BatchTaskStatus;
import com.wxx.aidocumentagent.ingestion.domain.IngestionJobStatus;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只基于数据库的 startedAt 恢复，不依赖某个 JVM 的内存计数器。 */
@Component
public class IngestionRecoveryScanner {

    private final DocumentIngestionJobRepository jobRepository;
    private final DocumentBatchTaskRepository batchTaskRepository;
    private final IngestionStaleProcessingRecovery recovery;
    private final IngestionProperties properties;

    public IngestionRecoveryScanner(DocumentIngestionJobRepository jobRepository,
                                    DocumentBatchTaskRepository batchTaskRepository,
                                    IngestionStaleProcessingRecovery recovery,
                                    IngestionProperties properties) {
        this.jobRepository = jobRepository;
        this.batchTaskRepository = batchTaskRepository;
        this.recovery = recovery;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.outbox.scan-delay:PT5S}", initialDelayString = "${app.ingestion.outbox.scan-delay:PT5S}")
    public void recoverStaleProcessing() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getStaleProcessingTimeout());
        jobRepository.findByStatusAndStartedAtBefore(IngestionJobStatus.PROCESSING, cutoff)
                .forEach(job -> recovery.recoverJob(job.getJobId(), job.getKnowledgeBaseId()));
        batchTaskRepository.findByStatusAndStartedAtBefore(BatchTaskStatus.PROCESSING, cutoff)
                .forEach(task -> recovery.recoverBatch(task.getBatchId(), task.getKnowledgeBaseId()));
    }
}
