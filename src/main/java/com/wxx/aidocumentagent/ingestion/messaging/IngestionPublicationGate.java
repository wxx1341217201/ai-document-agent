package com.wxx.aidocumentagent.ingestion.messaging;

import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.application.IngestionPublicationPendingException;
import com.wxx.aidocumentagent.ingestion.application.RetryableIngestionException;
import org.springframework.stereotype.Component;

/** Wait outside the rolled-back claim transaction, without spending a business retry on a confirm race. */
@Component
public class IngestionPublicationGate {
    private final long timeoutNanos;
    public IngestionPublicationGate(IngestionProperties properties) {
        timeoutNanos = properties.getPublisherConfirmTimeout().toNanos();
    }
    public void run(Runnable processing) {
        long deadline = System.nanoTime() + timeoutNanos;
        while (true) {
            try { processing.run(); return; }
            catch (IngestionPublicationPendingException pending) {
                if (System.nanoTime() >= deadline) throw pending;
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RetryableIngestionException("等待publisher状态提交时被中断", interrupted);
                }
            }
        }
    }
}
