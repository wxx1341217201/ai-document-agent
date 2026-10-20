package com.wxx.aidocumentagent.agent.execution.application;

import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** On startup and periodically, turn expired worker leases into resumable or failed executions. */
@Component
@ConditionalOnProperty(prefix = "app.agent.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionRecoveryScanner {
    private final ExecutionStore store;
    private final ExecutionApplicationService service;

    public ExecutionRecoveryScanner(ExecutionStore store, ExecutionApplicationService service) {
        this.store = store;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { scan(); }

    @Scheduled(fixedDelayString = "${app.agent.execution.scan-delay:PT5S}",
            initialDelayString = "${app.agent.execution.scan-delay:PT5S}")
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        for (var execution : store.stale(now, PageRequest.of(0, 100))) {
            store.recover(execution.getExecutionId(), execution.getKnowledgeBaseId(), now);
        }
        for (var execution : store.expiredWaiting(now, PageRequest.of(0, 100))) {
            store.expireWaiting(execution.getExecutionId(), execution.getKnowledgeBaseId(), now);
        }
        for (var execution : store.pending(PageRequest.of(0, 100))) {
            service.dispatchPending(execution.getExecutionId(), execution.getKnowledgeBaseId());
        }
    }
}
