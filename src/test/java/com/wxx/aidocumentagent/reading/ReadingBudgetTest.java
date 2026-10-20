package com.wxx.aidocumentagent.reading;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReadingBudgetTest {
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    @Test void commitsEachPermitBeforeDispatchAndCannotRefundFailures() {
        var state = new ReadingState(); List<Integer> committed = new ArrayList<>();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new ReadingBudget(state, now.plusSeconds(180), Clock.fixed(now, ZoneOffset.UTC),
                    () -> committed.add(state.modelCalls), calls);
            assertThatThrownBy(() -> budget.model("test", () -> { throw new IllegalStateException(); }))
                    .isInstanceOf(ReadingBudget.Interrupted.class);
            for (int i = 0; i < 7; i++) budget.model("test", () -> { assertThat(committed).hasSize(state.modelCalls); return true; });
            assertThatThrownBy(() -> budget.model("test", () -> { throw new AssertionError("over budget"); }))
                    .isInstanceOf(ReadingBudget.Exhausted.class);
        }
        assertThat(committed).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }
    @Test void refusesNewCallsAfterDeadlineWithoutDispatchingOrSpending() {
        var state = new ReadingState(); var dispatched = new AtomicInteger();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new ReadingBudget(state, now.minusMillis(1), Clock.fixed(now, ZoneOffset.UTC), () -> {}, calls);
            assertThatThrownBy(() -> budget.tool("test", dispatched::incrementAndGet)).isInstanceOf(ReadingBudget.Exhausted.class);
            assertThat(state.toolCalls).isZero(); assertThat(dispatched).hasValue(0);
        }
    }
    @Test void restoredBudgetUsesPriorCountersAndReservesFinalVerification() {
        var state = new ReadingState(); state.modelCalls = 6; state.toolCalls = 10;
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new ReadingBudget(state, now.plusSeconds(100), Clock.fixed(now, ZoneOffset.UTC), () -> {}, calls);
            assertThat(budget.canWork()).isTrue();
            budget.tool("work", () -> true); assertThat(budget.canWork()).isFalse();
            budget.model("final draft", () -> true); budget.model("verify", () -> true);
            budget.tool("source verify", () -> true);
            assertThatThrownBy(() -> budget.tool("excess", () -> true)).isInstanceOf(ReadingBudget.Exhausted.class);
            assertThat(state.modelCalls).isEqualTo(8); assertThat(state.toolCalls).isEqualTo(12);
        }
    }
    @Test void reservesFortySecondsForFinishing() {
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new ReadingBudget(new ReadingState(), now.plusSeconds(39), Clock.fixed(now, ZoneOffset.UTC), () -> {}, calls);
            assertThat(budget.canWork()).isFalse();
            assertThat(budget.model("final verify", () -> "ok")).isEqualTo("ok");
        }
    }
    @Test void sourceChangeSignalReachesWorkerWithoutBeingHiddenAsProviderFailure() {
        var state = new ReadingState();
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new ReadingBudget(state, now.plusSeconds(180), Clock.fixed(now, ZoneOffset.UTC), () -> {}, calls);
            assertThatThrownBy(() -> budget.tool("资料核验", () -> { throw new com.wxx.aidocumentagent.common.api.BusinessException(ReadingError.SOURCE_CHANGED); }))
                    .isInstanceOfSatisfying(com.wxx.aidocumentagent.common.api.BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ReadingError.SOURCE_CHANGED));
            assertThat(state.toolCalls).isEqualTo(1);
        }
    }
}
