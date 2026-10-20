package com.wxx.aidocumentagent.progress;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProgressEmitterTest {
    @Test
    void payloadRejectsArbitraryModelOrErrorText() {
        assertThatThrownBy(() -> ProgressPayload.agent("S1", "SUCCEEDED", "secret=raw-model-output", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProgressPayload.document(12, "FAILED", "文档处理失败", "internal stack"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void committedStatePublishesOnlyAfterCommit() {
        ProgressSequenceStore sequences = mock(ProgressSequenceStore.class);
        ProgressWebSocketBroker broker = mock(ProgressWebSocketBroker.class);
        ProgressEmitter emitter = new ProgressEmitter(sequences, broker);
        ProgressStream stream = ProgressStream.agent(7, "12345678-1234-1234-1234-123456789abc");
        when(sequences.next(stream)).thenReturn(1L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            emitter.afterCommit(stream, ProgressEventType.EXECUTION_COMPLETED,
                    ProgressPayload.agent(null, "SUCCEEDED", "执行已完成", null, null));
            verifyNoInteractions(sequences, broker);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(sequences).next(stream);
            verify(broker).publish(eq(stream), any(ProgressEvent.class));
        }
        finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
