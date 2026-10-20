package com.wxx.aidocumentagent.reading;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The database dispatch lock serializes workers across processes; expired jobs await manual recovery. */
public final class ReadingWorker {
    private static final Logger log = LoggerFactory.getLogger(ReadingWorker.class);
    private final ReadingStore store;
    private final ReadingSources sources;
    private final ReadingCoordinator coordinator;
    private final ExecutorService worker;
    private final ExecutorService calls;
    private final AtomicBoolean busy = new AtomicBoolean();
    public ReadingWorker(ReadingStore store, ReadingSources sources, ReadingCoordinator coordinator,
                         ExecutorService worker, ExecutorService calls) {
        this.store = store; this.sources = sources; this.coordinator = coordinator; this.worker = worker; this.calls = calls;
    }
    @Scheduled(fixedDelayString = "${app.reading.poll-ms:1500}")
    public void dispatch() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            var run = store.claim();
            if (run == null) { busy.set(false); return; }
            worker.submit(() -> { try { execute(run); } finally { busy.set(false); } });
        }
        catch (RuntimeException failure) {
            busy.set(false); log.warn("reading_dispatch_unavailable type={}", failure.getClass().getSimpleName());
        }
    }

    void execute(ReadingStore.Run initial) {
        var state = initial.state();
        ReadingStore.Run active = initial;
        ReadingBudget budget = null;
        try {
            if (!Instant.now().isBefore(initial.deadline())) {
                state.stopReason = "原轮时间已用尽；可手动继续补全开启新一轮";
                state.gaps.add("本轮未重新完成资料版本核验"); store.finish(initial, state, false); return;
            }
            var initialBudget = new ReadingBudget(state, initial.deadline(), Clock.systemUTC(),
                    () -> store.checkpoint(initial, state), calls);
            var current = initialBudget.tool("准备资料版本", () -> sources.snapshot(initial.knowledgeBaseId(), ReadingSources.scopeIds(initial.scope())));
            if (!current.equals(initial.scope())) {
                if (!state.sources.isEmpty() || !state.findings.isEmpty() || state.planned || !state.cursors.isEmpty())
                    state.invalidateChanged(initial.scope(), current);
                else state.stage("PLAN_EXECUTE_REFLECT", "source_snapshot", "SUCCEEDED", "已固定选定资料的版本");
            }
            state.materialGaps = (state.comprehensive || java.util.regex.Pattern.compile("图像|图表|公式|扫描|OCR|figure|equation", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(initial.goal()).find())
                    ? current.stream().flatMap(d -> d.limitations().stream().map(gap -> d.name() + "：" + gap))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)) : new ArrayList<>();
            active = store.refreshScope(initial, current, state);
            final var run = active;
            Runnable save = () -> store.checkpoint(run, state);
            budget = new ReadingBudget(state, run.deadline(), Clock.systemUTC(), save, calls);
            coordinator.execute(run, budget, save);
            budget.tool("资料版本核验", () -> { sources.verify(run.knowledgeBaseId(), run.scope(), state); return true; });
            if (!state.completion(run.scope()).equals("FULL") && state.stopReason == null)
                state.stopReason = "本轮已保存可靠成果，可按缺口继续补全";
            store.finish(run, state, false);
        }
        catch (ReadingBudget.Exhausted exhausted) {
            state.stopReason = exhausted.getMessage();
            state.gaps.add("本轮尚未完成全部核验步骤"); safeFinish(active, state, false);
        }
        catch (ReadingBudget.Interrupted interrupted) {
            state.stopReason = interrupted.getMessage();
            state.gaps.add("本轮执行中断，部分核验步骤尚未完成"); safeFinish(active, state, true);
        }
        catch (BusinessException changed) {
            if (changed.getErrorCode() == ReadingError.CONFLICT || changed.getErrorCode() == ReadingError.NOT_FOUND) return;
            state.findings.clear(); state.sources.clear(); state.cursors.clear(); state.pendingDraft = null;
            state.goals = state.goals.stream().map(g -> new Goal(g.id(), g.description(), g.query(), g.engine(), false))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            state.stopReason = "资料状态或版本已变化，请核对选定资料后手动恢复";
            state.gaps = new ArrayList<>(java.util.List.of("需要重新核验选定资料")); safeFinish(active, state, true);
        }
        catch (RuntimeException failure) {
            log.warn("reading_execution_interrupted task={} type={}", initial.id(), failure.getClass().getSimpleName());
            state.stopReason = "执行暂时中断，已保存阶段可手动恢复";
            state.gaps.add("本轮执行中断，部分核验步骤尚未完成"); safeFinish(active, state, true);
        }
    }

    private void safeFinish(ReadingStore.Run run, ReadingState state, boolean interrupted) {
        try { store.finish(run, state, interrupted); }
        catch (BusinessException fenced) { log.info("reading_stale_worker_fenced task={}", run.id()); }
        catch (RuntimeException unavailable) { log.warn("reading_checkpoint_waits_for_recovery task={}", run.id()); }
    }

}
