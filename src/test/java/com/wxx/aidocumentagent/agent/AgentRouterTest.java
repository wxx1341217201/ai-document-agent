package com.wxx.aidocumentagent.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRouterTest {

    @Test
    void 三种模式按枚举分派且默认检索优先() {
        DocumentAgent retrieval = agent(AgentMode.RETRIEVAL_FIRST);
        DocumentAgent react = agent(AgentMode.REACT);
        DocumentAgent plan = agent(AgentMode.PLAN_EXECUTE_REFLECT);
        AgentRouter router = new AgentRouter(List.of(retrieval, react, plan));

        assertThat(router.route(request(null))).isSameAs(retrieval);
        assertThat(router.route(request(AgentMode.RETRIEVAL_FIRST))).isSameAs(retrieval);
        assertThat(router.route(request(AgentMode.REACT))).isSameAs(react);
        assertThat(router.route(request(AgentMode.PLAN_EXECUTE_REFLECT))).isSameAs(plan);
    }

    @Test
    void 实现注册不依赖具体类且重复模式被拒绝() {
        DocumentAgent first = agent(AgentMode.REACT);
        DocumentAgent duplicate = agent(AgentMode.REACT);

        assertThatThrownBy(() -> new AgentRouter(List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复注册Agent模式");
        assertAgentError(() -> new AgentRouter(List.of()).route(request(null)),
                AgentErrorCode.AGENT_UNAVAILABLE);
    }

    @Test
    void 非白名单模式按400错误拒绝() {
        assertThat(AgentMode.fromClientValue(null)).isEqualTo(AgentMode.RETRIEVAL_FIRST);
        assertThat(AgentMode.fromClientValue("REACT")).isEqualTo(AgentMode.REACT);
        assertAgentError(() -> AgentMode.fromClientValue("java.lang.Runtime"), AgentErrorCode.INVALID_MODE);
        assertAgentError(() -> AgentMode.fromClientValue("react"), AgentErrorCode.INVALID_MODE);
        assertThat(AgentErrorCode.INVALID_MODE.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 实现异常归类为安全错误而不回显异常正文() {
        AgentRequest request = request(AgentMode.REACT);
        DocumentAgent react = agent(AgentMode.REACT);
        when(react.execute(same(request), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("secret downstream detail"));

        assertThatThrownBy(() -> new AgentRouter(List.of(react)).execute(request, context(request)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(AgentErrorCode.EXECUTION_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("secret downstream detail");
                });
    }

    @Test
    void Router调用选定实现且返回受知识库约束的最终结果() {
        AgentRequest request = request(null);
        AgentExecutionContext context = context(request);
        AgentResult result = completed(List.of(citation(7L)));
        DocumentAgent retrieval = agent(AgentMode.RETRIEVAL_FIRST);
        when(retrieval.execute(request, context)).thenReturn(result);

        assertThat(new AgentRouter(List.of(retrieval)).execute(request, context)).isSameAs(result);
        verify(retrieval).execute(request, context);
    }

    @Test
    void 跨知识库引用与上下文复用均被阻止() {
        AgentRequest request = request(null);
        DocumentAgent retrieval = agent(AgentMode.RETRIEVAL_FIRST);
        when(retrieval.execute(same(request), org.mockito.ArgumentMatchers.any()))
                .thenReturn(completed(List.of(citation(8L))));
        AgentRouter router = new AgentRouter(List.of(retrieval));
        AgentExecutionContext context = context(request);

        assertAgentError(() -> router.execute(request, context), AgentErrorCode.SCOPE_VIOLATION);
        assertAgentError(() -> router.execute(request, context), AgentErrorCode.CONTEXT_REUSED);

        AgentRequest anotherRequest = request(null);
        assertAgentError(() -> router.execute(anotherRequest, context), AgentErrorCode.INVALID_CONTEXT);
        assertAgentError(() -> AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5),
                new AgentConversationContext(8L, null, List.of()), event -> { }, new AgentCancellationToken()),
                AgentErrorCode.INVALID_CONTEXT);
    }

    @Test
    void 上下文快照与请求参数不会被原始容器后续修改污染() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("topK", "8");
        AgentRequest request = new AgentRequest("问题", 7L, 42L, null, parameters);
        List<AgentMemoryMessage> history = new ArrayList<>();
        history.add(new AgentMemoryMessage(1L, AgentMemoryMessage.Role.USER, "此前问题"));
        AgentConversationContext memory = new AgentConversationContext(7L, 42L, history);
        AgentExecutionContext context = AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(60),
                memory, event -> { }, new AgentCancellationToken());

        parameters.put("topK", "20");
        history.clear();
        assertThat(request.parameters()).containsEntry("topK", "8");
        assertThat(context.memory().messages()).hasSize(1);
        assertThatThrownBy(() -> context.memory().messages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 取消和截止时间由同一上下文约束() {
        AgentRequest request = request(null);
        AgentCancellationToken token = new AgentCancellationToken();
        AgentExecutionContext cancelled = AgentExecutionContext.forRequest(request, "trace",
                Instant.now().plusSeconds(60), AgentConversationContext.emptyFor(request), event -> { }, token);
        token.cancel();
        assertAgentError(cancelled::checkActive, AgentErrorCode.EXECUTION_CANCELLED);

        AgentExecutionContext expired = AgentExecutionContext.forRequest(request, "trace",
                Instant.now().minusSeconds(1), AgentConversationContext.emptyFor(request), event -> { },
                new AgentCancellationToken());
        assertAgentError(expired::checkActive, AgentErrorCode.DEADLINE_EXCEEDED);
        assertThat(expired.remainingTime()).isZero();
    }

    @Test
    void 事件自动携带请求边界而结果仅接受结构化摘要() {
        AgentRequest request = new AgentRequest("问题", 7L, 42L, AgentMode.REACT, Map.of());
        List<AgentEvent> events = new ArrayList<>();
        AgentExecutionContext context = AgentExecutionContext.forRequest(request, "trace-7",
                Instant.now().plusSeconds(60), AgentConversationContext.emptyFor(request), events::add,
                new AgentCancellationToken());

        context.publish(AgentMode.REACT, AgentEvent.Kind.STEP_UPDATED, "retrieve", null);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.traceId()).isEqualTo("trace-7");
            assertThat(event.knowledgeBaseId()).isEqualTo(7L);
            assertThat(event.conversationId()).isEqualTo(42L);
            assertThat(event.stepId()).isEqualTo("retrieve");
        });
        assertThatThrownBy(() -> new AgentStepSummary("step", AgentStepSummary.Kind.PLAN,
                "reasoning text with spaces", AgentStepSummary.Status.PENDING, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AgentRequest request(AgentMode mode) {
        return new AgentRequest("问题", 7L, null, mode, Map.of());
    }

    private AgentExecutionContext context(AgentRequest request) {
        return AgentExecutionContext.forRequest(request, "trace-7", Instant.now().plusSeconds(60),
                AgentConversationContext.emptyFor(request), event -> { }, new AgentCancellationToken());
    }

    private DocumentAgent agent(AgentMode mode) {
        DocumentAgent agent = mock(DocumentAgent.class);
        when(agent.mode()).thenReturn(mode);
        return agent;
    }

    private AgentResult completed(List<AgentCitation> citations) {
        return new AgentResult("最终答案", citations, List.of(), List.of(), AgentResult.Status.COMPLETED,
                AgentUsage.unknown(), null);
    }

    private AgentCitation citation(long knowledgeBaseId) {
        return new AgentCitation(knowledgeBaseId, "C1", 10L, "文档", 20L, null, null, "证据");
    }

    private void assertAgentError(Runnable action, AgentErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
