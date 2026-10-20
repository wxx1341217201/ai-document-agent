package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadingWorkerTest {
    private final ReadingStore store = mock(ReadingStore.class);
    private final ReadingSources sources = mock(ReadingSources.class);
    private final ReadingCoordinator coordinator = mock(ReadingCoordinator.class);

    @Test void expiredRoundNeverDispatchesNewSourceOrModelWork() {
        var run = run(Instant.now().minusSeconds(5)); run.state().modelCalls = 7;
        execute(run);
        verifyNoInteractions(sources, coordinator);
        verify(store).finish(eq(run), argThat(s -> s.modelCalls == 7 && s.completion(run.scope()).equals("NONE")), eq(false));
    }
    @Test void changedSourcesInvalidateAffectedArtifactsBeforeResuming() {
        var run = run(Instant.now().plusSeconds(180));
        var old = run.scope().getFirst();
        run.state().sources.add(new Source("C1", 15, "测试.txt", old.fingerprint(), 100, 0, "a".repeat(64), 0, "原文", null, null, null));
        run.state().findings.add(new Finding("F1", "G0", "FACT", "旧结论", List.of(new Support("C1", "原文"))));
        run.state().cursors.put(15L, new Cursor(1, 0, 1));
        var changed = List.of(new DocumentRef(15, "测试.txt", "b".repeat(64), "c".repeat(64), 2));
        when(sources.snapshot(eq(8L), anySet())).thenReturn(changed);
        when(store.refreshScope(any(), anyList(), any())).thenAnswer(a -> new ReadingStore.Run(run.id(), 8, run.goal(), 1,
                run.token(), run.deadline(), changed, a.getArgument(2)));
        doAnswer(a -> {
            ReadingStore.Run resumed = a.getArgument(0);
            assertThat(resumed.state().findings).isEmpty(); assertThat(resumed.state().sources).isEmpty();
            assertThat(resumed.state().cursors).isEmpty(); assertThat(resumed.state().goals).extracting(Goal::id).containsExactly("G0");
            return null;
        }).when(coordinator).execute(any(), any(), any());
        execute(run);
        verify(store).finish(any(), argThat(s -> s.toolCalls == 2 && s.findings.isEmpty()), eq(false));
    }
    @Test void fencedWorkerCannotDispatchEvenItsFirstSourceRequest() {
        var run = run(Instant.now().plusSeconds(180));
        doThrow(new BusinessException(ReadingError.CONFLICT)).when(store).checkpoint(any(), any());
        execute(run);
        verifyNoInteractions(sources, coordinator); verify(store, never()).finish(any(), any(), anyBoolean());
    }
    @Test void unavailableSourceKeepsBudgetSpentAndAwaitsManualRecovery() {
        var run = run(Instant.now().plusSeconds(180)); run.state().modelCalls = 3;
        when(sources.snapshot(anyLong(), anySet())).thenThrow(new IllegalStateException("private provider error"));
        execute(run);
        verify(store).finish(eq(run), argThat(s -> s.modelCalls == 3 && s.toolCalls == 1
                && !s.stopReason.contains("private")), eq(true));
    }
    private ReadingStore.Run run(Instant deadline) {
        var state = new ReadingState(); state.goals.add(new Goal("G0", "目标", "查询", "RETRIEVAL_FIRST", false));
        return new ReadingStore.Run("id", 8, "目标", 1, "token", deadline,
                List.of(new DocumentRef(15, "测试.txt", "b".repeat(64), "a".repeat(64), 2)), state);
    }
    private void execute(ReadingStore.Run run) {
        try (var worker = Executors.newSingleThreadExecutor(); var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            new ReadingWorker(store, sources, coordinator, worker, calls).execute(run);
        }
    }
}
