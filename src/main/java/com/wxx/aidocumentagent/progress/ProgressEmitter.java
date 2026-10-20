package com.wxx.aidocumentagent.progress;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Best effort notification; a failed socket or sequence write never rolls back authoritative state. */
@Component
@ConditionalOnProperty(prefix = "app.progress", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProgressEmitter {
    private static final Logger log = LoggerFactory.getLogger(ProgressEmitter.class);
    private final ProgressSequenceStore sequences;
    private final ProgressWebSocketBroker broker;

    public ProgressEmitter(ProgressSequenceStore sequences, ProgressWebSocketBroker broker) {
        this.sequences = sequences;
        this.broker = broker;
    }

    public void afterCommit(ProgressStream stream, ProgressEventType type, ProgressPayload payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { emit(stream, type, payload); }
            });
        }
        else emit(stream, type, payload);
    }

    /** Processing phase signals may precede commit; clients must query REST for committed status. */
    public void immediate(ProgressStream stream, ProgressEventType type, ProgressPayload payload) {
        emit(stream, type, payload);
    }

    // Serializes sequence allocation and local fanout so concurrent commits cannot reorder one stream.
    private synchronized void emit(ProgressStream stream, ProgressEventType type, ProgressPayload payload) {
        try {
            long sequence = sequences.next(stream);
            broker.publish(stream, new ProgressEvent(UUID.randomUUID(), stream.executionId(), sequence,
                    type, Instant.now(), payload));
        }
        catch (RuntimeException exception) {
            log.warn("进度通知未发送 resource={} executionId={} knowledgeBaseId={}", stream.resource(),
                    stream.executionId(), stream.knowledgeBaseId(), exception);
        }
    }
}
