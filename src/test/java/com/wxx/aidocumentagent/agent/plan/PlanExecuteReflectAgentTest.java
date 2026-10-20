package com.wxx.aidocumentagent.agent.plan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentEvent;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.react.AgentTool;
import com.wxx.aidocumentagent.agent.react.EvidenceChunk;
import com.wxx.aidocumentagent.agent.react.EvidenceResult;
import com.wxx.aidocumentagent.agent.react.ToolExecutionContext;
import com.wxx.aidocumentagent.agent.react.ToolRegistry;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanExecuteReflectAgentTest {
    private static final String TWO_SEARCHES = """
            {"goal":"比较 A 和 B","steps":[
              {"id":"S3","action":"SYNTHESIZE","dependsOn":["S1","S2"]},
              {"id":"S1","action":"SEARCH","query":"A"},
              {"id":"S2","action":"SEARCH","query":"B"}]}
            """;
    private static final String PARTIAL = """
            {"goal":"比较 A 和 B","steps":[
              {"id":"S1","action":"SEARCH","query":"A"},
              {"id":"S2","action":"SEARCH","query":"B"},
              {"id":"S3","action":"SYNTHESIZE","dependsOn":["S1"]}]}
            """;
    private static final String ONE_SEARCH = """
            {"goal":"比较 A 和 B","steps":[
              {"id":"S1","action":"SEARCH","query":"A"},
              {"id":"S2","action":"SYNTHESIZE","dependsOn":["S1"]}]}
            """;

    private final JsonMapper mapper = new JsonMapper();
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final PlanProperties properties = new PlanProperties();
    private final List<AgentEvent> events = new ArrayList<>();
    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        when(knowledgeBases.existsById(7L)).thenReturn(true);
        workers = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() { workers.close(); }

    @Test
    void 按拓扑执行并只引用真实成功证据() {
        AgentResult result = agent(search(input -> evidence(input.query().equals("A") ? 11 : 12, 7)),
                scripted(TWO_SEARCHES, "A 有重试 [C1]，B 有退避 [C2]。"))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.citations()).extracting(citation -> citation.chunkId()).containsExactly(11L, 12L);
        assertThat(result.steps().stream().filter(step -> step.operation().equals("SEARCH"))
                .map(AgentStepSummary::stepId)).containsExactly("r0_S1", "r0_S2");
        assertThat(result.steps().stream().filter(step -> step.operation().equals("SYNTHESIZE"))
                .findFirst().orElseThrow().dependsOn()).containsExactly("r0_S1", "r0_S2");
        assertThat(events.stream().filter(event -> event.kind() == AgentEvent.Kind.STEP_UPDATED)).isNotEmpty();
    }

    @Test
    void 独立步骤失败后继续并显式标注部分成功() {
        properties.setMaxRevisions(0);
        AgentResult result = agent(search(input -> {
            if (input.query().equals("B")) throw new IllegalStateException("secret-stack");
            return evidence(11, 7);
        }), scripted(PARTIAL, "A 有重试 [C1]。"))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).contains("部分检索未完成").doesNotContain("secret-stack");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(1).errorCode()).isEqualTo(AgentErrorCode.TOOL_FAILED);
    }

    @Test
    void 缺口只触发一次修订() {
        Queue<EvidenceResult> results = new ArrayDeque<>(List.of(new EvidenceResult(List.of()), evidence(11, 7)));
        AgentResult result = agent(search(input -> results.remove()),
                scripted(ONE_SEARCH, ONE_SEARCH, "补足证据后的结论 [C1]"))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).contains("补足证据");
        assertThat(result.steps()).anyMatch(step -> step.stepId().equals("r1_reflect"));
        assertThat(result.toolCalls()).hasSize(2);
    }

    @Test
    void 修订次数耗尽后不会继续调用模型或工具() {
        properties.setMaxRevisions(1);
        AgentResult result = agent(search(input -> new EvidenceResult(List.of())),
                scripted(ONE_SEARCH, ONE_SEARCH)).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).contains("资料不足");
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.usage().modelCalls()).isEqualTo(2);
    }

    @Test
    void 总工具预算耗尽立即停止() {
        properties.setMaxToolCalls(1);
        AgentResult result = agent(search(input -> evidence(11, 7)), scripted(TWO_SEARCHES))
                .execute(request(), context());
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.TOOL_CALL_LIMIT_EXCEEDED);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.steps()).anyMatch(step -> step.stepId().equals("r0_S3")
                && step.status() == AgentStepSummary.Status.SKIPPED);
    }

    @Test
    void 虚假引用不能进入最终答案() {
        properties.setMaxRevisions(0);
        AgentResult result = agent(search(input -> evidence(11, 7)),
                scripted(ONE_SEARCH, "伪造结论 [C2]"))
                .execute(request(), context());
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.INVALID_ANSWER);
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void 跨库证据不会进入综合或引用() {
        properties.setMaxRevisions(0);
        AgentResult result = agent(search(input -> evidence(11, 999)), scripted(ONE_SEARCH))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).contains("资料不足");
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void 模型超时返回有界失败() {
        properties.setModelTimeout(Duration.ofMillis(10));
        PlanModelClient slow = (system, user) -> {
            try { Thread.sleep(200); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return new PlanModelClient.Reply(ONE_SEARCH, null, null, null, null);
        };
        AgentResult result = agent(search(input -> evidence(11, 7)), slow).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.TIMED_OUT);
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.MODEL_TIMEOUT);
    }

    @Test
    void conversationContextReachesBothPlanningAndSynthesisWithoutReusingOldCitations() {
        List<String> prompts = new ArrayList<>();
        Queue<String> replies = new ArrayDeque<>(List.of(ONE_SEARCH, "因为预计下雨，简会留宿。[C1]"));
        PlanModelClient model = (system, user) -> {
            prompts.add(user);
            return new PlanModelClient.Reply(replies.remove(), "test", null, null, null);
        };
        var request = new AgentRequest("她母亲为什么建议这种出行方式？", 7, 42L, AgentMode.PLAN_EXECUTE_REFLECT, Map.of());
        when(conversations.existsByIdAndKnowledgeBaseIdAndAgentMode(42L, 7L,
                com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode.PLAN_EXECUTE_REFLECT)).thenReturn(true);
        var memory = new AgentConversationContext(7, 42L, List.of(
                new AgentMemoryMessage(1, AgentMemoryMessage.Role.USER, "Jane rides to Netherfield"),
                new AgentMemoryMessage(2, AgentMemoryMessage.Role.ASSISTANT, "previous context [C99]")));
        var context = AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5), memory,
                events::add, () -> false);
        var result = agent(search(input -> new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "guide", null, null,
                "Jane rides because the expected rain will make her stay all night")))), model).execute(request, context);
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(prompts).hasSize(2).allSatisfy(prompt -> assertThat(prompt).contains("Jane rides", "NOT evidence",
                "她母亲为什么").doesNotContain("[C99]"));
    }

    @Test
    void synthesisSeesLateEvidenceBeyondTheOldTwelveHundredCharacterLimit() {
        String content = "verified source ".repeat(100) + "late factual evidence";
        Queue<String> replies = new ArrayDeque<>(List.of(ONE_SEARCH, "late factual evidence [C1]"));
        PlanModelClient model = (system, user) -> {
            if (user.contains("Verified passages")) assertThat(user).contains(content);
            return new PlanModelClient.Reply(replies.remove(), "test", null, null, null);
        };
        var result = agent(search(input -> new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "guide", null, null,
                content)))), model).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).contains("late factual evidence");
    }

    @Test
    void nonemptyButUnusableEvidenceAndAnExplicitRefusalTriggerOnlyOneRevision() {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        var result = agent(search(input -> {
            int occurrence = counter.incrementAndGet();
            return new EvidenceResult(List.of(new EvidenceChunk(7, occurrence == 1 ? 11 : 12, 21, "guide", null, null,
                    occurrence == 1 ? "unrelated paragraph" : "verified relevant fact")));
        }),
                scripted(ONE_SEARCH, "无法从现有材料回答。", ONE_SEARCH, "verified relevant fact [C2]"))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.citations()).singleElement().extracting(citation -> citation.chunkId()).isEqualTo(12L);
        assertThat(result.usage().modelCalls()).isEqualTo(4);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.steps()).anyMatch(step -> step.stepId().equals("r1_reflect"));
        assertThat(result.steps()).noneMatch(step -> step.stepId().startsWith("r2_"));
    }

    @Test
    void synthesisUsesTheRegisteredTokenBoundedTextRatherThanTheRawToolPayload() {
        properties.setMaxEvidenceTokens(256);
        String source = "visible prefix ".repeat(1_000) + "unseen late fact";
        Queue<String> replies = new ArrayDeque<>(List.of(ONE_SEARCH, "The passage repeats a visible prefix [C1]"));
        PlanModelClient model = (system, user) -> {
            if (user.contains("Verified passages")) assertThat(user).contains("visible prefix").doesNotContain("unseen late fact");
            return new PlanModelClient.Reply(replies.remove(), "test", null, null, null);
        };
        var result = agent(search(input -> new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "guide", null, null,
                source)))), model).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.citations()).hasSize(1);
    }

    private PlanExecuteReflectAgent agent(AgentTool<SearchInput, EvidenceResult> tool, PlanModelClient model) {
        ToolRegistry registry = new ToolRegistry(List.of(tool), mapper,
                Validation.buildDefaultValidatorFactory().getValidator());
        return new PlanExecuteReflectAgent(knowledgeBases, conversations,
                new Planner(mapper, new PlanValidator(properties.getMaxSteps())),
                new Executor(registry, mapper, properties, workers), new Reflector(), model, properties, workers);
    }

    @Test
    void retriesEmptyResponseOnceAndCountsEveryAttemptWithoutInventingUsage() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        PlanModelClient model = (system, user) -> {
            int call = calls.incrementAndGet();
            return new PlanModelClient.Reply(call == 1 ? null : call == 2 ? ONE_SEARCH : "verified [C1]",
                    "test", 10, 5, 15);
        };
        var result = agent(search(input -> evidence(11, 7)), model).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.usage().modelCalls()).isEqualTo(3);
        assertThat(result.usage().totalTokens()).isNull();
    }

    @Test
    void neverRetriesAuthenticationFailureOrExceedsModelBudget() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        PlanModelClient unauthorized = (system, user) -> {
            calls.incrementAndGet();
            throw new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        };
        var result = agent(search(input -> evidence(11, 7)), unauthorized).execute(request(), context());
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.MODEL_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
        properties.setMaxModelCalls(1);
        PlanModelClient empty = (system, user) -> new PlanModelClient.Reply(null, null, null, null, null);
        result = agent(search(input -> evidence(11, 7)), empty).execute(request(), context());
        assertThat(result.usage().modelCalls()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
    }

    @Test
    void persistentServerFailureStopsAfterExactlyOneRetryAndCanDisableRetries() {
        PlanModelClient unavailable = (system, user) -> {
            throw new org.springframework.web.client.HttpServerErrorException(org.springframework.http.HttpStatus.BAD_GATEWAY);
        };
        var result = agent(search(input -> evidence(11, 7)), unavailable).execute(request(), context());
        assertThat(result.usage().modelCalls()).isEqualTo(2);
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.MODEL_UNAVAILABLE);
        properties.setMaxTransientRetries(0);
        result = agent(search(input -> evidence(11, 7)), unavailable).execute(request(), context());
        assertThat(result.usage().modelCalls()).isEqualTo(1);
    }

    @Test
    void prematureEndOfTheProviderResponseCanRecoverWithOneCountedRetry() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        PlanModelClient model = (system, user) -> {
            int call = calls.incrementAndGet();
            if (call == 1) throw new RuntimeException(new java.io.EOFException("private-body"));
            return new PlanModelClient.Reply(call == 2 ? ONE_SEARCH : "verified [C1]", "test", 10, 5, 15);
        };
        var result = agent(search(input -> evidence(11, 7)), model).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.usage().modelCalls()).isEqualTo(3);
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(result.answer()).doesNotContain("private-body");
    }

    @Test
    void semanticEvidenceGapTriggersOnlyOneRevisionAndIsNotABenchmarkGrade() {
        properties.setVerifyEvidence(true);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var result = agent(search(input -> new EvidenceResult(List.of(new EvidenceChunk(7,
                attempts.incrementAndGet() == 1 ? 11 : 12, 21, "guide", null, null,
                attempts.get() == 1 ? "first fact only" : "first and second facts")))),
                scripted(ONE_SEARCH, "first fact [C1]", "{\"verdict\":\"UNKNOWN\",\"missing\":[\"second fact\"]}",
                        ONE_SEARCH, "first and second facts [C2]", "{\"verdict\":\"SUPPORTED\",\"missing\":[]}"))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.usage().modelCalls()).isEqualTo(6);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.steps()).anyMatch(step -> step.stepId().equals("r0_reflect") && step.operation().equals("coverage_gap"));
        assertThat(result.steps()).anyMatch(step -> step.stepId().equals("r1_reflect") && step.operation().equals("coverage_complete"));
        assertThat(result.steps()).noneMatch(step -> step.stepId().startsWith("r2_"));
    }

    @Test
    void rejectsInvalidVerificationPayloadInsteadOfTreatingItAsEvidenceCoverage() {
        properties.setVerifyEvidence(true);
        var result = agent(search(input -> evidence(11, 7)),
                scripted(ONE_SEARCH, "fact [C1]", "{\"verdict\":\"SUPPORTED\",\"missing\":[],\"reasoning\":\"private-text\"}",
                        "{\"verdict\":\"SUPPORTED\",\"missing\":[],\"reasoning\":\"private-text\"}"))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.INVALID_ANSWER);
        assertThat(result.answer()).isNull();
        assertThat(result.usage().modelCalls()).isEqualTo(4);
    }

    private AgentTool<SearchInput, EvidenceResult> search(SearchBody body) {
        return new AgentTool<>() {
            @Override public String name() { return "searchKnowledgeBase"; }
            @Override public String description() { return "Search"; }
            @Override public Class<SearchInput> inputType() { return SearchInput.class; }
            @Override public EvidenceResult execute(SearchInput input, ToolExecutionContext context) {
                return body.execute(input);
            }
            @Override public Map<String, String> summarize(SearchInput input) {
                return Map.of("queryLength", Integer.toString(input.query().length()));
            }
        };
    }

    @Test
    void structuredSynthesisAndVerifierSchemaRepairAreBothBudgeted() {
        properties.setStructuredAnswers(true);
        properties.setVerifyEvidence(true);
        var result = agent(search(input -> evidence(11, 7)), scripted(ONE_SEARCH,
                "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"核验的段落。\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"经过数据库核验的段落\"}]}],\"missing\":[]}",
                "{\"verdict\":\"SUPPORTED\",\"missing\":[],\"extra\":true}", "{\"verdict\":\"SUPPORTED\",\"missing\":[]}"))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).isEqualTo("核验的段落。 [C1]");
        assertThat(result.usage().modelCalls()).isEqualTo(4);
    }

    private EvidenceResult evidence(long chunkId, long knowledgeBaseId) {
        return new EvidenceResult(List.of(new EvidenceChunk(knowledgeBaseId, chunkId, 21, "指南",
                1, 1, "经过数据库核验的段落")));
    }

    private PlanModelClient scripted(String... replies) {
        Queue<String> queue = new ArrayDeque<>(List.of(replies));
        return (system, user) -> new PlanModelClient.Reply(queue.remove(), "test-model", null, null, null);
    }

    private AgentRequest request() {
        return new AgentRequest("比较 A 和 B", 7L, null, AgentMode.PLAN_EXECUTE_REFLECT, Map.of());
    }

    private AgentExecutionContext context() {
        AgentRequest request = request();
        return AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), events::add, () -> false);
    }

    private record SearchInput(@NotBlank String query) { }
    @FunctionalInterface private interface SearchBody { EvidenceResult execute(SearchInput input); }
}
