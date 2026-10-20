package com.wxx.aidocumentagent.agent.benchmark;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.plan.*;
import com.wxx.aidocumentagent.agent.react.*;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.json.JsonMapper;

import static com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentBenchmarkAgentFlowTest {
    private final JsonMapper mapper = new JsonMapper();
    private final KnowledgeBaseRepository knowledgeBases = org.mockito.Mockito.mock(KnowledgeBaseRepository.class);
    private final ConversationRepository conversations = org.mockito.Mockito.mock(ConversationRepository.class);
    private static final String PLAN = """
            {"goal":"author","steps":[{"id":"S1","action":"SEARCH","query":"author"},
            {"id":"S2","action":"SYNTHESIZE","dependsOn":["S1"]}]}
            """;

    @Test void actualPlanLoopUsesRealSearchResultsAfterOneScopedGap() {
        org.mockito.Mockito.when(knowledgeBases.existsById(2L)).thenReturn(true);
        var task = task(AgentMode.PLAN_EXECUTE_REFLECT, new AgentBenchmarkDataset.Fault(1, "searchKnowledgeBase", 1, ToolAction.EMPTY_EVIDENCE), true);
        var trace = new AgentBenchmarkTrace(task.fault(), 1); var calls = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var factory = Validation.buildDefaultValidatorFactory()) {
            var tools = new ToolRegistry(List.of(search(calls)), mapper, factory.getValidator());
            Queue<String> replies = new ArrayDeque<>(List.of(PLAN, PLAN, "Lewis Carroll [C1]."));
            PlanModelClient model = (system, user) -> new PlanModelClient.Reply(replies.remove(), "scripted-unit-model", null, null, null);
            var props = new PlanProperties();
            var agent = new PlanExecuteReflectAgent(knowledgeBases, conversations, new Planner(mapper, new PlanValidator(8)),
                    new com.wxx.aidocumentagent.agent.plan.Executor(tools, mapper, props, workers), new Reflector(), model, props, workers);
            var request = new AgentRequest(task.turns().getFirst(), 2, null, task.mode(), Map.of());
            var result = new AgentRouter(List.of(agent)).execute(request, context(request, trace));
            var snapshot = trace.freeze();
            assertThat(calls.get()).isEqualTo(2); assertThat(snapshot.evidence()).hasSize(1);
            assertThat(result.steps()).anyMatch(s -> s.stepId().equals("r0_reflect") && s.operation().equals("coverage_gap"));
            assertThat(snapshot.dispatches()).extracting(AgentBenchmarkTrace.Dispatch::action).containsExactly(ToolAction.EMPTY_EVIDENCE, ToolAction.RUN);
            assertThat(result.steps().stream().filter(s -> s.kind() == AgentStepSummary.Kind.PLAN)).hasSize(2);
            assertThat(replies).isEmpty();
            var obs = observed(task, result, snapshot);
            var evaluator = new AgentBenchmarkEvaluator(mapper);
            assertThat(evaluator.evaluate("run", "d".repeat(64), task, obs, review(evaluator, task, obs)).status()).isEqualTo(AgentBenchmarkEvaluator.Status.SUCCEEDED);
        }
    }
    @Test void actualReactLoopAuditsFailureThenRecoversWithChangedQuery() {
        org.mockito.Mockito.when(knowledgeBases.existsById(2L)).thenReturn(true);
        var task = task(AgentMode.REACT, new AgentBenchmarkDataset.Fault(1, "searchKnowledgeBase", 1, ToolAction.FAIL), true);
        var trace = new AgentBenchmarkTrace(task.fault(), 1); var calls = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var factory = Validation.buildDefaultValidatorFactory()) {
            var tools = new ToolRegistry(List.of(search(calls)), mapper, factory.getValidator());
            Queue<ChatResponse> replies = new ArrayDeque<>(List.of(toolCall("author"), toolCall("author adjusted"),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("<think>HIDDEN_UNIT_REASONING</think>Lewis Carroll [C1]."))))));
            ReActModelClient model = (messages, definitions) -> {
                if (replies.size() == 2) assertThat(mapper.writeValueAsString(messages)).contains("TOOL_FAILED").doesNotContain("HIDDEN_UNIT_REASONING");
                return replies.remove();
            };
            var agent = new ReActAgent(knowledgeBases, conversations, model, tools, new ReActProperties(), workers, mapper);
            var request = new AgentRequest(task.turns().getFirst(), 2, null, task.mode(), Map.of());
            var result = new AgentRouter(List.of(agent)).execute(request, context(request, trace));
            var snapshot = trace.freeze();
            assertThat(calls.get()).isEqualTo(1); assertThat(result.toolCalls()).hasSize(2);
            assertThat(snapshot.dispatches()).extracting(AgentBenchmarkTrace.Dispatch::action).containsExactly(ToolAction.FAIL, ToolAction.RUN);
            assertThat(mapper.writeValueAsString(snapshot)).doesNotContain("HIDDEN_UNIT_REASONING");
            var obs = observed(task, result, snapshot); var evaluator = new AgentBenchmarkEvaluator(mapper);
            assertThat(evaluator.evaluate("run", "d".repeat(64), task, obs, review(evaluator, task, obs)).status()).isEqualTo(AgentBenchmarkEvaluator.Status.SUCCEEDED);
        }
    }
    @Test void normalRequestParametersCannotEnableFaultsAndFrozenTraceDropsLateEvidence() {
        var calls = new AtomicInteger();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var tools = new ToolRegistry(List.of(search(calls)), mapper, factory.getValidator());
            var request = new AgentRequest("author", 2, null, AgentMode.REACT, Map.of("fault", "FAIL"));
            var context = AgentExecutionContext.forRequest(request, "normal", Instant.now().plusSeconds(5), AgentConversationContext.emptyFor(request), e -> {}, () -> false);
            assertThat(tools.execute("searchKnowledgeBase", "{\"query\":\"author\"}", new ToolExecutionContext(2, context, context.deadline(), 10)).success()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
        }
        var trace = new AgentBenchmarkTrace(null, 1); trace.freeze(); trace.evidence(CITATION, TEXT);
        assertThat(trace.freeze().evidence()).isEmpty();
    }
    private AgentTool<SearchKnowledgeBaseTool.Input, EvidenceResult> search(AtomicInteger calls) {
        return new AgentTool<>() {
            public String name() { return "searchKnowledgeBase"; }
            public boolean readOnly() { return true; }
            public String description() { return "Unit fixture search"; }
            public Class<SearchKnowledgeBaseTool.Input> inputType() { return SearchKnowledgeBaseTool.Input.class; }
            public EvidenceResult execute(SearchKnowledgeBaseTool.Input input, ToolExecutionContext context) {
                assertThat(context.knowledgeBaseId()).isEqualTo(2); calls.incrementAndGet();
                return new EvidenceResult(List.of(new EvidenceChunk(2, 250, 20, "unit-fixture.txt", null, null, TEXT)));
            }
            public Map<String, String> summarize(SearchKnowledgeBaseTool.Input input) { return Map.of("queryLength", "6"); }
        };
    }
    private AgentExecutionContext context(AgentRequest request, AgentBenchmarkTrace trace) {
        return AgentExecutionContext.forObservedRequest(request, "unit-trace", Instant.now().plusSeconds(10),
                AgentConversationContext.emptyFor(request), e -> {}, () -> false, trace);
    }
    private AgentBenchmarkEvaluator.Observation observed(AgentBenchmarkDataset.Task task, com.wxx.aidocumentagent.agent.AgentResult result,
                                                          AgentBenchmarkTrace.Snapshot snapshot) {
        var t = new AgentBenchmarkEvaluator.Turn(1, task.turns().getFirst(), AgentBenchmarkText.canonicalHash(mapper, List.of()), 0, null, result, List.of(EVIDENCE),
                result.steps(), result.toolCalls(), snapshot.dispatches(), true, false, 2, null);
        return new AgentBenchmarkEvaluator.Observation(true, List.of(t), 2, null);
    }
    private ChatResponse toolCall(String query) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                query.replace(" ", "-"), "function", "searchKnowledgeBase", mapper.writeValueAsString(Map.of("query", query))))).build())));
    }
}
