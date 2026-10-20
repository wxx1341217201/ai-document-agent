package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.wxx.aidocumentagent.agent.*;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionRunner;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ExecutionRunnerCheckpointObserverTest {
    @Test void observerRunsAfterDurableCheckpointAndBeforeFinalization() {
        ExecutionStore store = mock(ExecutionStore.class);
        AgentRouter router = mock(AgentRouter.class);
        ExecutionCheckpointObserver observer = mock(ExecutionCheckpointObserver.class);
        AgentExecution execution = AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                new JsonMapper().valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        String token = execution.claim(LocalDateTime.now(), LocalDateTime.now().plusMinutes(2), false);
        when(store.get(execution.getExecutionId(), 7L)).thenReturn(execution);
        AgentResult result = new AgentResult("final answer", List.of(), List.of(), List.of(), AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);
        when(router.execute(any(), any())).thenReturn(result);
        ExecutionRunner runner = new ExecutionRunner(store, router, event -> { }, mock(ConversationRepository.class),
                mock(ObjectProvider.class), new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties());
        runner.setCheckpointObserver(observer);
        runner.runClaimed(execution.getExecutionId(), 7L, token);
        var order = inOrder(store, observer);
        order.verify(store).get(execution.getExecutionId(), 7L);
        order.verify(store).checkpoint(execution.getExecutionId(), 7L, token, result);
        order.verify(observer).afterResultCheckpoint(execution.getExecutionId(), 7L);
        order.verify(store).complete(execution.getExecutionId(), 7L, token);
    }
    @Test void observerFailureAfterCommittedResultDoesNotOverwriteResultWithFailure() {
        ExecutionStore store = mock(ExecutionStore.class);
        AgentRouter router = mock(AgentRouter.class);
        AgentExecution execution = AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                new JsonMapper().valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        String token = execution.claim(LocalDateTime.now(), LocalDateTime.now().plusMinutes(2), false);
        when(store.get(execution.getExecutionId(), 7L)).thenReturn(execution);
        AgentResult result = new AgentResult("final answer", List.of(), List.of(), List.of(), AgentResult.Status.COMPLETED, AgentUsage.unknown(), null);
        when(router.execute(any(), any())).thenReturn(result);
        doAnswer(call -> { execution.checkpoint(new JsonMapper().valueToTree(result)); return null; }).when(store).checkpoint(execution.getExecutionId(), 7L, token, result);
        ExecutionRunner runner = new ExecutionRunner(store, router, event -> { }, mock(ConversationRepository.class),
                mock(ObjectProvider.class), new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties());
        runner.setCheckpointObserver((id, kb) -> { throw new IllegalStateException("injected observer failure"); });
        runner.runClaimed(execution.getExecutionId(), 7L, token);
        verify(store, never()).fail(any(), anyLong(), any(), any(), anyBoolean());
        verify(store, never()).complete(any(), anyLong(), any());
    }
}
