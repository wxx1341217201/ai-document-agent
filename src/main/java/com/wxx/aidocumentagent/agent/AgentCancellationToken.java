package com.wxx.aidocumentagent.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/** 每次执行单独创建的可取消信号。 */
public final class AgentCancellationToken implements AgentCancellationSignal {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }
}
