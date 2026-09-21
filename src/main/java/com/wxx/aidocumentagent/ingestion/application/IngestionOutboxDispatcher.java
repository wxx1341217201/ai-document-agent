package com.wxx.aidocumentagent.ingestion.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 先由提交后事件低延迟投递，再由扫描器补偿崩溃或发布失败窗口。 */
@Service
public class IngestionOutboxDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionOutboxDispatcher.class);

    private final IngestionOutboxLifecycleService lifecycleService;
    private final IngestionMessagePublisher messagePublisher;
    private final com.wxx.aidocumentagent.ingestion.IngestionProperties properties;

    public IngestionOutboxDispatcher(IngestionOutboxLifecycleService lifecycleService,
                                     IngestionMessagePublisher messagePublisher,
                                     com.wxx.aidocumentagent.ingestion.IngestionProperties properties) {
        this.lifecycleService = lifecycleService;
        this.messagePublisher = messagePublisher;
        this.properties = properties;
    }

    public void dispatch(String eventId) {
        if (!properties.isEnabled()) {
            return;
        }
        lifecycleService.claim(eventId).ifPresent(envelope -> {
            try {
                messagePublisher.publish(envelope);
                lifecycleService.markPublished(envelope.eventId());
            }
            catch (RuntimeException exception) {
                LOGGER.warn("摄取outbox发布未确认，将由有限补偿重试: eventId={}", envelope.eventId());
                lifecycleService.releaseAfterPublishFailure(envelope.eventId());
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.ingestion.outbox.scan-delay:PT5S}", initialDelayString = "${app.ingestion.outbox.scan-delay:PT5S}")
    public void dispatchPendingEvents() {
        if (!properties.isEnabled()) {
            return;
        }
        lifecycleService.releaseExpiredDispatchLeases();
        for (String eventId : lifecycleService.dispatchableEventIds()) {
            dispatch(eventId);
        }
    }
}
