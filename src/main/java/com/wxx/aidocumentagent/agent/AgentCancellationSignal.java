package com.wxx.aidocumentagent.agent;

/** 合作式取消信号，Agent 应在每次耗时操作前检查。 */
@FunctionalInterface
public interface AgentCancellationSignal {

    boolean isCancelled();
}
