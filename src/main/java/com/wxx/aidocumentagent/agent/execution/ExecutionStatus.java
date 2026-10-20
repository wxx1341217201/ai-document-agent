package com.wxx.aidocumentagent.agent.execution;

/** The persisted state machine; terminal states cannot be claimed again. */
public enum ExecutionStatus {
    PENDING, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED
}
