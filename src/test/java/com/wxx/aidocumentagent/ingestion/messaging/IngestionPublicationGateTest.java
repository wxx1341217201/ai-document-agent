package com.wxx.aidocumentagent.ingestion.messaging;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.application.IngestionPublicationPendingException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionPublicationGateTest {
    private IngestionPublicationGate gate(Duration timeout) {
        var properties = new IngestionProperties();
        properties.setPublisherConfirmTimeout(timeout);
        return new IngestionPublicationGate(properties);
    }
    @Test void waitsForConfirmCommitWithoutRetryingSuccessfulProcessing() {
        var claims = new AtomicInteger();
        var processed = new AtomicInteger();
        gate(Duration.ofSeconds(1)).run(() -> {
            if (claims.incrementAndGet() < 3) throw new IngestionPublicationPendingException("pending");
            processed.incrementAndGet();
        });
        assertThat(claims).hasValue(3);
        assertThat(processed).hasValue(1);
    }
    @Test void handsTimeoutBackToExistingBoundedRetryPolicy() {
        assertThatThrownBy(() -> gate(Duration.ZERO).run(() -> { throw new IngestionPublicationPendingException("pending"); }))
                .isInstanceOf(IngestionPublicationPendingException.class);
    }
    @Test void doesNotRetryBusinessOrProtocolFailures() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> gate(Duration.ofSeconds(1)).run(() -> {
            attempts.incrementAndGet(); throw new IllegalArgumentException("scope mismatch");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts).hasValue(1);
    }
    @Test void preservesInterruptForConsumerShutdown() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gate(Duration.ofSeconds(1)).run(() -> { throw new IngestionPublicationPendingException("pending"); }))
                    .isInstanceOf(com.wxx.aidocumentagent.ingestion.application.RetryableIngestionException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
