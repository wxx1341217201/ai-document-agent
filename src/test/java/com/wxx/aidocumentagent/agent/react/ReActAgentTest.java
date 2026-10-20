package com.wxx.aidocumentagent.agent.react;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReActAgentTest {

    private final JsonMapper mapper = new JsonMapper();
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ReActProperties properties = new ReActProperties();
    private final AtomicInteger executions = new AtomicInteger();
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        when(knowledgeBases.existsById(7L)).thenReturn(true);
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void 正常多步完成且仅记录安全参数摘要() {
        AgentTool<SearchInput, EvidenceResult> search = searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "指南", 1, 1, "第一段证据")));
        });
        AgentTool<ChunkInput, EvidenceResult> neighbor = neighborTool((input, context) -> {
            assertThat(context.isAuthorizedChunk(input.chunkId())).isTrue();
            return new EvidenceResult(List.of(new EvidenceChunk(7, 12, 21, "指南", 1, 1, "第二段证据")));
        });
        List<List<Message>> seen = new ArrayList<>();
        ReActModelClient model = scripted(seen, toolCall("searchKnowledgeBase", "{\"query\":\"secret query\"}"),
                toolCall("getChunkContext", "{\"chunkId\":11}"), answer("结论 [C2]"));

        AgentResult result = agent(List.of(search, neighbor), model).execute(request(), context());

        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().getFirst().chunkId()).isEqualTo(12);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().getFirst().argumentsSummary()).containsEntry("queryLength", "12");
        assertThat(result.toolCalls().toString()).doesNotContain("secret query");
        assertThat(seen.get(1)).anyMatch(message -> message instanceof ToolResponseMessage);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void 未注册工具不能执行且返回结构化观察() {
        List<List<Message>> seen = new ArrayList<>();
        ReActModelClient model = scripted(seen, toolCall("executeSql", "{}"), answer("无法回答"));
        AgentResult result = agent(List.of(searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of());
        })), model).execute(request(), context());
        assertThat(executions.get()).isZero();
        assertThat(result.toolCalls().getFirst().errorCode()).isEqualTo(AgentErrorCode.UNKNOWN_TOOL);
        ToolResponseMessage observation = (ToolResponseMessage) seen.get(1).getLast();
        assertThat(observation.getResponses().getFirst().responseData()).contains("UNKNOWN_TOOL");
    }

    @Test
    void 非法参数和跨库字段阻止工具执行() {
        ReActModelClient model = scripted(new ArrayList<>(),
                toolCall("searchKnowledgeBase", "{\"query\":\"x\",\"knowledgeBaseId\":999}"), answer("无法回答"));
        AgentResult result = agent(List.of(searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of());
        })), model).execute(request(), context());
        assertThat(executions.get()).isZero();
        assertThat(result.toolCalls().getFirst().errorCode()).isEqualTo(AgentErrorCode.INVALID_TOOL_ARGUMENTS);
    }

    @Test
    void BeanValidation拒绝空查询且不执行工具() {
        AgentResult result = agent(List.of(searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of());
        })), scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"  \"}"),
                answer("无法回答"))).execute(request(), context());
        assertThat(executions.get()).isZero();
        assertThat(result.toolCalls().getFirst().errorCode()).isEqualTo(AgentErrorCode.INVALID_TOOL_ARGUMENTS);
    }

    @Test
    void 重复调用到阈值时停止() {
        ReActModelClient model = scripted(new ArrayList<>(),
                toolCall("searchKnowledgeBase", "{\"query\":\"same\"}"),
                toolCall("searchKnowledgeBase", "{\"query\":\"same\"}"));
        AgentResult result = agent(List.of(searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of());
        })), model).execute(request(), context());
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.REPEATED_TOOL_CALL);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void 最大步数限制先于工具执行() {
        properties.setMaxSteps(1);
        AgentResult result = agent(List.of(searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of());
        })), scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"x\"}")))
                .execute(request(), context());
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.STEP_LIMIT_EXCEEDED);
        assertThat(executions.get()).isZero();
    }

    @Test
    void 工具超时安全停止() {
        properties.setToolTimeout(Duration.ofMillis(10));
        AgentResult result = agent(List.of(searchTool((input, context) -> {
            try { Thread.sleep(200); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return new EvidenceResult(List.of());
        })), scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"x\"}")))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.TIMED_OUT);
        assertThat(result.toolCalls().getFirst().errorCode()).isEqualTo(AgentErrorCode.TOOL_TIMEOUT);
    }

    @Test
    void 总截止时间会终止慢模型调用() {
        properties.setDeadline(Duration.ofMillis(15));
        properties.setModelTimeout(Duration.ofSeconds(1));
        ReActModelClient slow = (messages, definitions) -> {
            try { Thread.sleep(200); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return answer("迟到的回答");
        };
        AgentResult result = agent(List.of(), slow).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.TIMED_OUT);
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.MODEL_TIMEOUT);
    }

    @Test
    void 工具异常只成为安全错误观察() {
        List<List<Message>> seen = new ArrayList<>();
        AgentResult result = agent(List.of(searchTool((input, context) -> {
            throw new IllegalStateException("database-secret-stack");
        })), scripted(seen, toolCall("searchKnowledgeBase", "{\"query\":\"x\"}"), answer("无法回答")))
                .execute(request(), context());
        assertThat(result.toolCalls().getFirst().errorCode()).isEqualTo(AgentErrorCode.TOOL_FAILED);
        ToolResponseMessage observation = (ToolResponseMessage) seen.get(1).getLast();
        assertThat(observation.getResponses().getFirst().responseData()).contains("TOOL_FAILED")
                .doesNotContain("database-secret-stack");
    }

    @Test
    void 跨库证据不会授权或返回() {
        AgentResult result = agent(List.of(searchTool((input, context) ->
                new EvidenceResult(List.of(new EvidenceChunk(999, 11, 21, "外库", 1, 1, "外库内容"))))),
                scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"x\"}"),
                        answer("可能的回答"))).execute(request(), context());
        assertThat(result.citations()).isEmpty();
        assertThat(result.answer()).contains("资料不足");
    }

    @Test
    void toolBudgetReservesAFinalModelCallWithoutExecutingMoreTools() {
        properties.setMaxToolCalls(1);
        properties.setMaxSteps(2);
        AtomicInteger calls = new AtomicInteger();
        ReActModelClient model = (messages, definitions) -> {
            if (calls.getAndIncrement() == 0) {
                assertThat(definitions).isNotEmpty();
                return toolCall("searchKnowledgeBase", "{\"query\":\"fact\"}");
            }
            assertThat(definitions).isEmpty();
            return answer("verified fact [C1]");
        };
        var result = agent(List.of(searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "guide", null, null, "verified fact")));
        })), model).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.usage().modelCalls()).isEqualTo(2);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void oversizedToolBatchGetsPairedDenialsThenSynthesizesExistingEvidence() {
        properties.setMaxToolCalls(2);
        properties.setMaxSteps(3);
        AtomicInteger calls = new AtomicInteger();
        ReActModelClient model = (messages, definitions) -> {
            int call = calls.getAndIncrement();
            if (call == 0) return toolCall("searchKnowledgeBase", "{\"query\":\"fact\"}");
            if (call == 1) return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("a", "function", "searchKnowledgeBase", "{\"query\":\"extra one\"}"),
                            new AssistantMessage.ToolCall("b", "function", "searchKnowledgeBase", "{\"query\":\"extra two\"}"))).build())));
            assertThat(definitions).isEmpty();
            var response = (ToolResponseMessage) messages.getLast();
            assertThat(response.getResponses()).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("a", "b");
            assertThat(response.getResponses()).allSatisfy(item -> assertThat(item.responseData()).contains("STEP_LIMIT_EXCEEDED"));
            return answer("verified fact [C1]");
        };
        var result = agent(List.of(searchTool((input, context) -> {
            executions.incrementAndGet();
            return new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "guide", null, null, "verified fact")));
        })), model).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.usage().modelCalls()).isEqualTo(3);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void unrelatedNonemptyEvidenceAllowsCanonicalRefusalWithoutInventingACitation() {
        var result = agent(List.of(searchTool((input, context) -> new EvidenceResult(List.of(
                        new EvidenceChunk(7, 11, 21, "guide", null, null, "unrelated passage"))))),
                scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"missing fact\"}"),
                        answer("无法从现有材料回答这个问题。"))).execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).isEqualTo("现有资料不足以回答这个问题，请补充相关资料。");
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void repeatedEvidenceUsesTheExistingCitationWithoutRepeatingItsBody() {
        List<List<Message>> seen = new ArrayList<>();
        var result = agent(List.of(searchTool((input, context) -> new EvidenceResult(List.of(
                        new EvidenceChunk(7, 11, 21, "guide", null, null, "verified fact body"))))),
                scripted(seen, toolCall("searchKnowledgeBase", "{\"query\":\"one\"}"),
                        toolCall("searchKnowledgeBase", "{\"query\":\"two\"}"), answer("fact [C1]")))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        var response = (ToolResponseMessage) seen.get(2).getLast();
        assertThat(response.getResponses().getFirst().responseData()).contains("alreadyAvailable", "C1")
                .doesNotContain("verified fact body");
        assertThat(result.citations()).hasSize(1);
    }

    private ReActAgent agent(List<AgentTool<?, ?>> tools, ReActModelClient model) {
        ToolRegistry registry = new ToolRegistry(tools, mapper,
                Validation.buildDefaultValidatorFactory().getValidator());
        return new ReActAgent(knowledgeBases, conversations, model, registry, properties, executor, mapper);
    }

    @Test
    void performsFreshSearchWhenTheProviderIgnoresForcedToolChoice() {
        List<List<Message>> seen = new ArrayList<>();
        var result = agent(List.of(searchTool((input, context) -> {
            assertThat(input.query()).isEqualTo("问题");
            return new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "guide", null, null, "verified fact")));
        })), scripted(seen, answer("现有资料不足以回答。"), answer("verified fact [C1]")))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.toolCalls()).singleElement().extracting(call -> call.toolName()).isEqualTo("searchKnowledgeBase");
        assertThat(result.usage().modelCalls()).isEqualTo(2);
        assertThat(seen.getLast().getLast()).isInstanceOf(ToolResponseMessage.class);
    }

    @Test
    void executesUserRequestedMetadataAndNeighborsBeforeAcceptingTheFinalAnswer() {
        AgentTool<GetDocumentMetadataTool.Input, GetDocumentMetadataTool.Output> metadata = new AgentTool<>() {
            public String name() { return "getDocumentMetadata"; }
            public String description() { return "Read metadata"; }
            public Class<GetDocumentMetadataTool.Input> inputType() { return GetDocumentMetadataTool.Input.class; }
            public GetDocumentMetadataTool.Output execute(GetDocumentMetadataTool.Input input, ToolExecutionContext context) {
                assertThat(input.documentId()).isEqualTo(21);
                return new GetDocumentMetadataTool.Output(true, 21L, "guide", "text/plain", 100L, "READY");
            }
        };
        AgentTool<GetChunkContextTool.Input, EvidenceResult> neighbor = new AgentTool<>() {
            public String name() { return "getChunkContext"; }
            public String description() { return "Read neighbors"; }
            public Class<GetChunkContextTool.Input> inputType() { return GetChunkContextTool.Input.class; }
            public EvidenceResult execute(GetChunkContextTool.Input input, ToolExecutionContext context) {
                assertThat(context.isAuthorizedChunk(input.chunkId())).isTrue();
                assertThat(input.radius()).isEqualTo(1);
                return new EvidenceResult(List.of(new EvidenceChunk(7, 12, 21, "guide", null, null, "adjacent fact")));
            }
        };
        var request = new AgentRequest("先核对文档元数据并读取相邻片段，再回答问题", 7, null, AgentMode.REACT, Map.of());
        var context = AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> { }, () -> false);
        var result = agent(List.of(searchTool((input, tc) -> new EvidenceResult(List.of(
                new EvidenceChunk(7, 11, 21, "guide", null, null, "verified fact")))), metadata, neighbor),
                scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"fact\"}"),
                        answer("premature [C1]"), answer("adjacent fact [C2]"))).execute(request, context);
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.toolCalls()).extracting(call -> call.toolName())
                .containsExactly("searchKnowledgeBase", "getDocumentMetadata", "getChunkContext");
        assertThat(result.usage().modelCalls()).isEqualTo(3);
    }

    @Test
    void aToolFailureCanRetryOnceButSuccessfulRepeatedCallsStillStop() {
        var attempts = new AtomicInteger();
        var result = agent(List.of(searchTool((input, context) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("private failure");
            return new EvidenceResult(List.of(new EvidenceChunk(7, 11, 21, "guide", null, null, "verified fact")));
        })), scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"same\"}"),
                toolCall("searchKnowledgeBase", "{\"query\":\"same\"}"), answer("verified fact [C1]")))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().getFirst().errorCode()).isEqualTo(AgentErrorCode.TOOL_FAILED);
        assertThat(result.toolCalls().getLast().errorCode()).isNull();
        assertThat(result.answer()).doesNotContain("private failure");
    }

    @Test
    void uncitedFactualDraftCanReadNeighborsOnceAndFinishWithinTheOriginalBudget() {
        var seen = new ArrayList<List<Message>>();
        var result = agent(List.of(searchTool((input, context) -> new EvidenceResult(List.of(
                new EvidenceChunk(7, 11, 21, "guide", null, null, "introductory passage")))),
                neighborTool((input, context) -> new EvidenceResult(List.of(
                        new EvidenceChunk(7, 12, 21, "guide", null, null, "verified adjacent fact"))))),
                scripted(seen, toolCall("searchKnowledgeBase", "{\"query\":\"fact\"}"), answer("unsupported fact without citation"),
                        toolCall("getChunkContext", "{\"chunkId\":11}"), answer("verified adjacent fact [C2]")))
                .execute(request(), context());
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.usage().modelCalls()).isEqualTo(4);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.steps()).anyMatch(step -> step.operation().equals("citation_gap"));
        assertThat(result.steps()).noneMatch(step -> step.operation().equals("safe_stop"));
        assertThat(seen.get(2).toString()).doesNotContain("unsupported fact without citation");
    }

    @Test
    void invalidCitedDraftIsRepairedAtMostOnce() {
        var result = agent(List.of(searchTool((input, context) -> new EvidenceResult(List.of(
                new EvidenceChunk(7, 11, 21, "guide", null, null, "verified fact"))))),
                scripted(new ArrayList<>(), toolCall("searchKnowledgeBase", "{\"query\":\"fact\"}"),
                        answer("invented [C999]"), answer("still invented [C998]")))
                .execute(request(), context());
        assertThat(result.failure().code()).isEqualTo(AgentErrorCode.INVALID_ANSWER);
        assertThat(result.usage().modelCalls()).isEqualTo(3);
        assertThat(result.toolCalls()).hasSize(1);
    }

    private AgentRequest request() { return new AgentRequest("问题", 7, null, AgentMode.REACT, Map.of()); }

    @Test
    void structuredAnswerBindsRealQuotesAndVerifiesSupportWithinTheOriginalBudget() {
        properties.setStructuredAnswers(true);
        List<List<Message>> seen = new ArrayList<>();
        String draft = "{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"Retry 5 times.\",\"supports\":[{\"citationId\":\"C1\",\"quote\":\"Retry 5 times.\"}]}],\"missing\":[]}";
        var result = agent(List.of(searchTool((input, context) -> new EvidenceResult(List.of(
                new EvidenceChunk(7, 11, 21, "policy", null, null, "Retry 5 times."))))),
                scripted(seen, toolCall("searchKnowledgeBase", "{\"query\":\"retry\"}"), answer(draft),
                        answer("{\"verdict\":\"SUPPORTED\",\"missing\":[]}"))).execute(request(), context());
        assertThat(result.answer()).isEqualTo("Retry 5 times. [C1]");
        assertThat(result.usage().modelCalls()).isEqualTo(3);
        assertThat(seen.get(1)).anyMatch(message -> message.getText().contains("CURRENT VERIFIED EVIDENCE WINDOW"));
        var old = seen.get(1).stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).findFirst().orElseThrow();
        assertThat(old.getResponses().getFirst().responseData()).doesNotContain("Retry 5 times.");
    }

    private AgentExecutionContext context() {
        AgentRequest request = request();
        return AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> { }, () -> false);
    }

    private ReActModelClient scripted(List<List<Message>> seen, ChatResponse... responses) {
        Queue<ChatResponse> queue = new ArrayDeque<>(List.of(responses));
        return (messages, definitions) -> {
            seen.add(messages);
            assertThat(List.of(definitions).stream().map(callback -> callback.getToolDefinition().name()))
                    .doesNotContain("executeSql");
            return queue.remove();
        };
    }

    private ChatResponse toolCall(String name, String args) {
        AssistantMessage output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, args))).build();
        return new ChatResponse(List.of(new Generation(output)));
    }

    private ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private AgentTool<SearchInput, EvidenceResult> searchTool(ToolBody<SearchInput> body) {
        return new AgentTool<>() {
            @Override public String name() { return "searchKnowledgeBase"; }
            @Override public String description() { return "Search documents"; }
            @Override public Class<SearchInput> inputType() { return SearchInput.class; }
            @Override public EvidenceResult execute(SearchInput input, ToolExecutionContext context) {
                return body.execute(input, context);
            }
            @Override public Map<String, String> summarize(SearchInput input) {
                return Map.of("queryLength", Integer.toString(input.query().length()));
            }
        };
    }

    private AgentTool<ChunkInput, EvidenceResult> neighborTool(ToolBody<ChunkInput> body) {
        return new AgentTool<>() {
            @Override public String name() { return "getChunkContext"; }
            @Override public String description() { return "Read neighbors"; }
            @Override public Class<ChunkInput> inputType() { return ChunkInput.class; }
            @Override public EvidenceResult execute(ChunkInput input, ToolExecutionContext context) {
                return body.execute(input, context);
            }
        };
    }

    private record SearchInput(@NotBlank String query) { }
    private record ChunkInput(long chunkId) { }
    @FunctionalInterface private interface ToolBody<I> { EvidenceResult execute(I input, ToolExecutionContext context); }
}
