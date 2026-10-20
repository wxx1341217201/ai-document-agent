package com.wxx.aidocumentagent.recovery.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import com.wxx.aidocumentagent.agent.*;
import com.wxx.aidocumentagent.agent.execution.ExecutionCheckpointObserver;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.application.*;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.*;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryFixtureServiceTest {
    @TempDir Path runtime;
    private static final String RUN = "c69f70e8-dc24-4422-8b3e-25e9f871333c";
    private static final String ID = "c69f70e8-dc24-4422-8b3e-25e9f871334c";
    private ConversationRepository conversations;
    private ConversationMessageRepository messages;
    private StringRedisTemplate redis;
    private ExecutionStore store;
    private RecoveryFixtureService fixture;
    @BeforeEach void setup() {
        conversations = mock(ConversationRepository.class); messages = mock(ConversationMessageRepository.class);
        redis = mock(StringRedisTemplate.class); store = mock(ExecutionStore.class);
        var mapper = new JsonMapper();
        fixture = new RecoveryFixtureService(mock(KnowledgeBaseRepository.class), conversations, messages,
                mock(ConversationMemoryService.class), new ConversationMessagePayloadCodec(mapper), redis, store, mapper,
                new MockEnvironment().withProperty("M19D_RUN_ID", RUN).withProperty("M19D_ACCESS_TOKEN", "test-token-" + "x".repeat(32))
                        .withProperty("M19D_RUNTIME_DIRECTORY", runtime.toString()));
        ReflectionTestUtils.setField(fixture, "knowledgeBaseId", 7L);
        var conversation = Conversation.create(7, "m19d:" + RUN + ":case", ConversationAgentMode.REACT);
        when(conversations.findByIdAndKnowledgeBaseId(42, 7)).thenReturn(Optional.of(conversation));
        var execution = mock(AgentExecution.class); when(execution.getConversationId()).thenReturn(42L);
        when(store.get(ID, 7)).thenReturn(execution);
    }
    @Test void wrongKnowledgeBaseNeverReachesRedisOrMessageRepository() {
        assertThatThrownBy(() -> fixture.cache(8, 42, "DELETE")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.snapshot(8, 42)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(redis, messages);
        verify(conversations, never()).findByIdAndKnowledgeBaseId(42, 8);
    }
    @Test void unownedConversationCannotBeFaulted() {
        when(conversations.findByIdAndKnowledgeBaseId(42, 7)).thenReturn(Optional.of(Conversation.create(7, "user conversation", ConversationAgentMode.REACT)));
        assertThatThrownBy(() -> fixture.cache(7, 42, "DELETE")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(redis);
    }
    @Test void invalidAuthorizationNeverAcceptsBenchmarkSecretPrefix() {
        assertThatThrownBy(() -> fixture.authorize("Bearer test-token-")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.authorize(null)).isInstanceOf(BusinessException.class);
        assertThatCode(() -> fixture.authorize("Bearer test-token-" + "x".repeat(32))).doesNotThrowAnyException();
    }
    @Test void realWorkerThreadInterruptAtBoundaryProducesControlledFailure() throws Exception {
        var request = new AgentRequest("case|STEP", 7, 42L, AgentMode.REACT, Map.of());
        var context = AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(10),
                new AgentConversationContext(7, 42L, List.of()), event -> { }, new AgentCancellationToken());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { fixture.awaitRelease(ID, context); } catch (Throwable error) { failure.set(error); } });
        worker.start();
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
            while (!Boolean.TRUE.equals(fixture.attempts(7, ID).get("waiting")) && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(fixture.interrupt(7, ID).get("interruptSent")).isEqualTo(true);
            worker.join(2000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure.get()).getErrorCode()).isEqualTo(AgentErrorCode.TOOL_FAILED);
        } finally { worker.interrupt(); worker.join(2000); }
    }
    @Test void releaseMarkerAllowsContinuationAndAttemptsAreJournaled() throws Exception {
        Files.writeString(runtime.resolve(ID + ".release"), "RELEASE\n");
        assertThatCode(() -> fixture.awaitRelease(ID, null)).doesNotThrowAnyException();
        fixture.recordAttempt(ID);
        assertThat(fixture.attempts(7, ID).get("agentAttempts")).isEqualTo(1);
    }
    @Test void controlledBoundaryStillHonorsTheAgentDeadline() {
        var request = new AgentRequest("case|STEP", 7, 42L, AgentMode.REACT, Map.of());
        var context = AgentExecutionContext.forRequest(request, "trace", Instant.now().minusSeconds(1),
                new AgentConversationContext(7, 42L, List.of()), event -> { }, new AgentCancellationToken());
        assertThatThrownBy(() -> fixture.awaitRelease(ID, context)).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(AgentErrorCode.DEADLINE_EXCEEDED));
        assertThat(fixture.attempts(7, ID).get("waiting")).isEqualTo(false);
    }
    @Test void benchmarkNodeAndFaultControllerAreDisabledByDefault() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(RecoveryBenchmarkNode.NodeConfiguration.class, RecoveryFixtureController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RecoveryFixtureService.class);
                    assertThat(context).doesNotHaveBean(RecoveryFixtureController.class);
                    assertThat(context).doesNotHaveBean(ExecutionCheckpointObserver.class);
                });
    }
}
