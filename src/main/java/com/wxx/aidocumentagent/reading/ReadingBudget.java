package com.wxx.aidocumentagent.reading;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Each permit is committed before dispatch. Recovery cannot refund an in-flight call. */
public final class ReadingBudget {
    public static final int MODEL_LIMIT = 8;
    public static final int TOOL_LIMIT = 12;
    public static final Duration ROUND = Duration.ofSeconds(180);
    private final ReadingState state;
    private final Instant deadline;
    private final Clock clock;
    private final Runnable checkpoint;
    private final ExecutorService calls;

    public ReadingBudget(ReadingState state, Instant deadline, Clock clock, Runnable checkpoint, ExecutorService calls) {
        this.state = state; this.deadline = deadline; this.clock = clock; this.checkpoint = checkpoint; this.calls = calls;
    }

    public boolean canWork() {
        return state.modelCalls <= MODEL_LIMIT - 2 && state.toolCalls < TOOL_LIMIT - 1
                && clock.instant().isBefore(deadline.minusSeconds(40));
    }

    public <T> T model(String phase, Callable<T> action) {
        return invoke(true, phase, action);
    }
    public <T> T tool(String phase, Callable<T> action) {
        return invoke(false, phase, action);
    }

    private <T> T invoke(boolean model, String phase, Callable<T> action) {
        if (!clock.instant().isBefore(deadline)) throw new Exhausted("本轮时间已用尽");
        if (model && state.modelCalls >= MODEL_LIMIT || !model && state.toolCalls >= TOOL_LIMIT)
            throw new Exhausted("本轮调用额度已用尽");
        if (model) state.modelCalls++; else state.toolCalls++;
        checkpoint.run();
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        if (remaining <= 0) throw new Exhausted("本轮时间已用尽");
        var future = calls.submit(action);
        try {
            return future.get(Math.min(35_000, remaining), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new Interrupted("执行已中断");
        }
        catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true); throw new Interrupted(phase + "超时，可手动恢复");
        }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exhausted exhausted) throw exhausted;
            if (failure.getCause() instanceof com.wxx.aidocumentagent.common.api.BusinessException business
                    && business.getErrorCode() instanceof ReadingError) throw business;
            throw new Interrupted(phase + "暂不可用，可手动恢复");
        }
    }

    public static class Exhausted extends RuntimeException {
        public Exhausted(String message) { super(message); }
    }
    public static class Interrupted extends RuntimeException {
        public Interrupted(String message) { super(message); }
    }
}
