package com.wxx.aidocumentagent.ingestion.application;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 事务提交后异步触发，确保 HTTP 上传请求不等待 publisher confirm 或 RabbitMQ 可用性。 */
@Component
public class IngestionOutboxDispatchListener {

    private final IngestionOutboxDispatcher dispatcher;

    public IngestionOutboxDispatchListener(IngestionOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Async("ingestionDispatchExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxCreated(IngestionOutboxCreatedEvent event) {
        dispatcher.dispatch(event.eventId());
    }
}
