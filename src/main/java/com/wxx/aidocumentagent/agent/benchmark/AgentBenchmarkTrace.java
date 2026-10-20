package com.wxx.aidocumentagent.agent.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentExecutionProbe;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;

/** 每轮独占；冻结后丢弃取消超时的后台任务事件，不串到下一轮。 */
final class AgentBenchmarkTrace implements AgentExecutionProbe {
    private final AgentBenchmarkDataset.Fault fault;
    private final int turn;
    private final List<Evidence> evidence = new ArrayList<>();
    private final Map<String, AgentStepSummary> steps = new LinkedHashMap<>();
    private final List<AgentToolCallSummary> tools = new ArrayList<>();
    private final List<Dispatch> dispatches = new ArrayList<>();
    private final List<Map<String, Object>> diagnostics = new ArrayList<>();
    private final Map<String, Integer> occurrences = new LinkedHashMap<>();
    private boolean frozen;

    AgentBenchmarkTrace(AgentBenchmarkDataset.Fault fault, int turn) { this.fault = fault; this.turn = turn; }

    @Override public synchronized void evidence(AgentCitation citation, String contextText) {
        if (!frozen) {
            Evidence item = new Evidence(citation, contextText);
            if (!evidence.contains(item)) evidence.add(item);
        }
    }
    @Override public synchronized void step(AgentStepSummary summary) {
        if (!frozen) steps.put(summary.stepId(), summary);
    }
    @Override public synchronized void tool(AgentToolCallSummary summary) {
        if (!frozen) tools.add(summary);
    }
    @Override public synchronized void diagnostic(String stage, Map<String, Object> details) {
        if (!frozen && diagnostics.size() < 256) diagnostics.add(Map.of("stage", stage, "details", Map.copyOf(details)));
    }
    @Override public synchronized ToolAction beforeTool(long kb, String name, Map<String, String> summary) {
        if (frozen) throw new IllegalStateException("OBSERVATION_CLOSED");
        int ordinal = occurrences.merge(name, 1, Integer::sum);
        ToolAction action = fault != null && fault.turn() == turn && fault.toolName().equals(name)
                && fault.occurrence() == ordinal ? fault.action() : ToolAction.RUN;
        dispatches.add(new Dispatch(kb, name, ordinal, action, summary, Map.of()));
        return action;
    }
    @Override public synchronized void toolResult(String name, Map<String, String> summary) {
        if (frozen) return;
        for (int i = dispatches.size() - 1; i >= 0; i--) {
            Dispatch before = dispatches.get(i);
            if (before.toolName().equals(name) && before.resultSummary().isEmpty()) {
                dispatches.set(i, new Dispatch(before.knowledgeBaseId(), name, before.occurrence(),
                        before.action(), before.argumentsSummary(), summary));
                return;
            }
        }
    }
    synchronized Snapshot freeze() {
        frozen = true;
        return new Snapshot(List.copyOf(evidence), List.copyOf(steps.values()), List.copyOf(tools), List.copyOf(dispatches), List.copyOf(diagnostics));
    }
    record Evidence(AgentCitation citation, String contextText) { }
    record Dispatch(long knowledgeBaseId, String toolName, int occurrence, ToolAction action,
                    Map<String, String> argumentsSummary, Map<String, String> resultSummary) {
        Dispatch { argumentsSummary = Map.copyOf(argumentsSummary); resultSummary = Map.copyOf(resultSummary); }
    }
    record Snapshot(List<Evidence> evidence, List<AgentStepSummary> steps, List<AgentToolCallSummary> tools,
                    List<Dispatch> dispatches, List<Map<String, Object>> diagnostics) { }
}
