package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionStateTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void duplicateClaimCannotAdvanceAndTerminalCannotReturnToRunning() {
        AgentExecution execution = fresh();
        LocalDateTime now = LocalDateTime.now();
        String owner = execution.claim(now, now.plusMinutes(2), false);
        assertThat(execution.ownedBy(owner)).isTrue();
        assertThatThrownBy(() -> execution.claim(now, now.plusMinutes(2), false))
                .isInstanceOf(IllegalStateException.class);
        execution.checkpoint(mapper.valueToTree(Map.of("answer", "done")));
        execution.finishSuccess(now.plusSeconds(1));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThatThrownBy(() -> execution.claim(now.plusSeconds(2), now.plusMinutes(3), true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.requestCancel(now.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedAgentCheckpointSurvivesInterruptedFinalization() {
        AgentExecution execution = fresh();
        LocalDateTime now = LocalDateTime.now();
        String oldToken = execution.claim(now, now.plusMinutes(2), false);
        var safeResult = mapper.valueToTree(Map.of("answer", "checkpoint"));
        execution.checkpoint(safeResult);
        execution.recover(now.plusMinutes(3));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(execution.getResultJson()).isEqualTo(safeResult);
        assertThat(execution.ownedBy(oldToken)).isFalse();
        String newToken = execution.claim(now.plusMinutes(4), now.plusMinutes(6), true);
        assertThat(execution.getCurrentStep()).isEqualTo("finalize");
        assertThat(execution.ownedBy(newToken)).isTrue();
        execution.finishSuccess(now.plusMinutes(4));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void cancellationAtBoundaryBeatsSuccessfulCompletion() {
        AgentExecution execution = fresh();
        LocalDateTime now = LocalDateTime.now();
        execution.claim(now, now.plusMinutes(2), false);
        execution.requestCancel(now.plusSeconds(1));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.isCancelRequested()).isTrue();
        execution.checkpoint(mapper.valueToTree(Map.of("answer", "done")));
        execution.finishSuccess(now.plusSeconds(2));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void failedResumeRequiresRetryableFlagAndCheckpointRequiresRunning() {
        AgentExecution execution = fresh();
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> execution.checkpoint(mapper.valueToTree(Map.of("answer", "x"))))
                .isInstanceOf(IllegalStateException.class);
        execution.claim(now, now.plusMinutes(2), false);
        execution.finishFailure("INVALID_PLAN", false, now.plusSeconds(1));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThatThrownBy(() -> execution.claim(now.plusSeconds(2), now.plusMinutes(3), true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleRunningTaskBecomesFailedAfterRecoveryDeadline() {
        AgentExecution execution = fresh();
        LocalDateTime now = LocalDateTime.now();
        execution.claim(now, now.plusMinutes(2), false);
        execution.recover(now.plusHours(2));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorCode()).isEqualTo("EXECUTION_EXPIRED");
    }

    @Test
    void pendingTaskExpiresInsteadOfRemainingQueuedForever() {
        AgentExecution execution = fresh();
        execution.expireWaiting(LocalDateTime.now().plusHours(2));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorCode()).isEqualTo("EXECUTION_EXPIRED");
    }

    @Test
    void durableResultCanFinishAfterRecoveryWindowExpires() {
        AgentExecution execution = fresh();
        LocalDateTime now = LocalDateTime.now();
        execution.claim(now, now.plusMinutes(2), false);
        execution.checkpoint(mapper.valueToTree(Map.of("answer", "saved")));
        execution.recover(now.plusHours(2));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void simultaneousResumeClaimsOnlyOneOwner() throws Exception {
        AgentExecution execution = fresh();
        LocalDateTime now = LocalDateTime.now();
        execution.claim(now, now.plusMinutes(2), false);
        execution.recover(now.plusMinutes(3));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    execution.claim(now.plusMinutes(4), now.plusMinutes(6), true);
                    winners.incrementAndGet();
                }
                catch (IllegalStateException ignored) { }
                return null;
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
        }
        assertThat(winners).hasValue(1);
    }

    private AgentExecution fresh() {
        return AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                mapper.valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
    }
}
