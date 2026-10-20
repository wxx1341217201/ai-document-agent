package com.wxx.aidocumentagent.agent.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentToolCallSummary;
import com.wxx.aidocumentagent.agent.AgentUsage;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset.RequiredFact;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.FactReview;

final class AgentBenchmarkFixtures {
    static final String TEXT = "by Lewis Carroll";
    static final AgentCitation CITATION = new AgentCitation(2, "C1", 20, "unit-fixture.txt", 250, null, null, TEXT);
    static final RagEvidenceChunk EVIDENCE = new RagEvidenceChunk(2, AgentBenchmarkEvaluator.toRag(CITATION), TEXT, AgentBenchmarkText.sha256(TEXT));
    static AgentBenchmarkDataset.Task task(AgentMode mode) { return task(mode, null, true); }
    static AgentBenchmarkDataset.Task task(AgentMode mode, AgentBenchmarkDataset.Fault fault, boolean answerable) {
        List<String> tools = mode == AgentMode.RETRIEVAL_FIRST ? List.of() : List.of("searchKnowledgeBase");
        List<String> tags = new ArrayList<>(List.of(answerable ? "SINGLE_DOCUMENT" : "REFUSAL"));
        if (fault != null) tags.add(fault.action() == ToolAction.FAIL ? "TOOL_FAILURE" : "REPLAN");
        return new AgentBenchmarkDataset.Task("UNIT-001", 2, mode, List.of("Who wrote the book?"), 4, tags, answerable,
                answerable ? "Lewis Carroll." : "Insufficient evidence.", answerable ? List.of(new RequiredFact("author", "Lewis Carroll is the author.",
                    List.of("Lewis Carroll"), List.of(250L), TEXT)) : List.of(), 1.0, answerable ? List.of(250L) : List.of(),
                tools, tools.isEmpty() ? Map.of() : Map.of("searchKnowledgeBase", 1), AgentBenchmarkDataset.FORBIDDEN.stream().sorted().toList(),
                answerable, AgentResult.Status.COMPLETED, mode == AgentMode.RETRIEVAL_FIRST ? 0 : 12, 12,
                mode == AgentMode.PLAN_EXECUTE_REFLECT ? 1 : 0, fault != null && fault.action() == ToolAction.EMPTY_EVIDENCE ? 1 : 0,
                fault, "unit_test_fixture");
    }
    static AgentBenchmarkEvaluator.Observation observation(AgentBenchmarkDataset.Task task) {
        List<AgentToolCallSummary> tools = task.mode() == AgentMode.RETRIEVAL_FIRST ? List.of()
                : List.of(new AgentToolCallSummary("searchKnowledgeBase", AgentStepSummary.Status.COMPLETED, 1L, null));
        List<AgentBenchmarkTrace.Dispatch> dispatches = tools.isEmpty() ? List.of() : List.of(new AgentBenchmarkTrace.Dispatch(2,
                "searchKnowledgeBase", 1, ToolAction.RUN, Map.of(), Map.of("status", "OK", "evidenceChunks", task.answerable() ? "1" : "0")));
        var result = new AgentResult(task.answerable() ? "Lewis Carroll [C1]." : "现有资料不足以回答，请补充资料。",
                task.answerable() ? List.of(CITATION) : List.of(), List.of(), tools, AgentResult.Status.COMPLETED,
                new AgentUsage(null, null, null, 1, tools.size()), null, "unit-test-model");
        var turn = new AgentBenchmarkEvaluator.Turn(1, task.turns().getFirst(), AgentBenchmarkText.canonicalHash(new tools.jackson.databind.json.JsonMapper(), List.of()), 0, null,
                result, task.answerable() ? List.of(EVIDENCE) : List.of(), List.of(), tools, dispatches, true, false, 2, null);
        return new AgentBenchmarkEvaluator.Observation(true, List.of(turn), 2, null);
    }
    static AgentBenchmarkEvaluator.Review review(AgentBenchmarkEvaluator evaluator, AgentBenchmarkDataset.Task task,
                                                 AgentBenchmarkEvaluator.Observation observation) {
        return new AgentBenchmarkEvaluator.Review("run", "d".repeat(64), task.caseId(), evaluator.resultHash("run", "d".repeat(64), task, observation),
                "unit-test-reviewer", "2026-10-02T00:00:00Z", true, task.requiredFacts().stream().map(f -> new FactReview(f.id(), true, List.of("C1"))).toList(),
                true, true, true, true, true, "Fixture only, not a measured benchmark.");
    }
    static AgentBenchmarkEvaluator.Observation replace(AgentBenchmarkEvaluator.Observation obs, AgentResult result,
                                                       List<AgentToolCallSummary> tools, List<AgentStepSummary> steps,
                                                       List<AgentBenchmarkTrace.Dispatch> dispatches) {
        var t = obs.turns().getFirst();
        var replacement = new AgentBenchmarkEvaluator.Turn(t.turn(), t.question(), t.inputMemorySha256(), t.inputMemoryMessages(), t.conversationId(),
                result, t.evidence(), steps, tools, dispatches, t.evidenceVerified(), t.hiddenReasoningDetected(), t.elapsedMillis(), t.errorCode());
        return new AgentBenchmarkEvaluator.Observation(true, List.of(replacement), obs.elapsedMillis(), obs.errorCode());
    }
}
