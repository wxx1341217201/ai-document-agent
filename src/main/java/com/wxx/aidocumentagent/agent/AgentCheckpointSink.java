package com.wxx.aidocumentagent.agent;

/** Receives only structured step states and safe tool summaries from a durable run. */
public interface AgentCheckpointSink {
    void step(AgentStepSummary summary);
    void tool(String stepId, AgentToolCallSummary summary);

    AgentCheckpointSink NO_OP = new AgentCheckpointSink() {
        @Override public void step(AgentStepSummary summary) { }
        @Override public void tool(String stepId, AgentToolCallSummary summary) { }
    };
}
