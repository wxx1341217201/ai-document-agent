package com.wxx.aidocumentagent.recovery.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Balanced, seeded fault assignment. The frozen plan is written before any injection. */
public record RecoveryBenchmarkPlan(long seed, List<Sample> samples) {
    public enum Fault {
        REDIS_KEY_LOSS, REDIS_TTL_EXPIRY, REDIS_RESTART, APPLICATION_RESTART,
        AGENT_STEP_INTERRUPTION, WORKER_REDELIVERY, WEBSOCKET_RECONNECT
    }

    public record Sample(String caseId, Fault fault, int historyTurns) { }

    public RecoveryBenchmarkPlan {
        samples = List.copyOf(samples);
        if (samples.isEmpty() || samples.stream().map(Sample::caseId).distinct().count() != samples.size()) {
            throw new IllegalArgumentException("INVALID_RECOVERY_PLAN");
        }
    }

    public static RecoveryBenchmarkPlan generate(int count, long seed) {
        if (count < Fault.values().length || count > 100_000) throw new IllegalArgumentException("INVALID_SAMPLE_COUNT");
        List<Fault> faults = new ArrayList<>();
        for (int i = 0; i < count; i++) faults.add(Fault.values()[i % Fault.values().length]);
        Collections.shuffle(faults, new Random(seed));
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Some histories exceed the production memory window: trimming must not count as durable loss.
            samples.add(new Sample("RECOVERY-%05d".formatted(i + 1), faults.get(i), i % 10 == 0 ? 12 : 3));
        }
        return new RecoveryBenchmarkPlan(seed, samples);
    }
}
