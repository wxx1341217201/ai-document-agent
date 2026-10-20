package com.wxx.aidocumentagent.progress;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

public record ProgressEvent(UUID eventId, String executionId, long sequence, ProgressEventType type,
                            Instant timestamp, ProgressPayload payload) {
    public ProgressEvent {
        Objects.requireNonNull(eventId);
        UUID.fromString(executionId);
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(type);
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(payload);
    }
}
