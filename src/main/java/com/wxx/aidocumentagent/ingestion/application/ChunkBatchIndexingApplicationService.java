package com.wxx.aidocumentagent.ingestion.application;

import com.wxx.aidocumentagent.ingestion.messaging.ChunkBatchMessage;
import org.springframework.stereotype.Service;

/** 将数据库状态领取、受限数据读取、索引端口和最终聚合编排为一个 batch worker。 */
@Service
public class ChunkBatchIndexingApplicationService {

    private final ChunkBatchTaskLifecycleService lifecycleService;
    private final ChunkBatchContentReader contentReader;
    private final ChunkBatchIndexingProcessor indexingProcessor;

    public ChunkBatchIndexingApplicationService(ChunkBatchTaskLifecycleService lifecycleService,
                                                ChunkBatchContentReader contentReader,
                                                ChunkBatchIndexingProcessor indexingProcessor) {
        this.lifecycleService = lifecycleService;
        this.contentReader = contentReader;
        this.indexingProcessor = indexingProcessor;
    }

    public void process(ChunkBatchMessage message) {
        lifecycleService.claim(message).ifPresent(work -> {
            ChunkBatchIndexingRequest request = contentReader.read(work);
            indexingProcessor.process(request);
            lifecycleService.complete(work);
        });
    }
}
