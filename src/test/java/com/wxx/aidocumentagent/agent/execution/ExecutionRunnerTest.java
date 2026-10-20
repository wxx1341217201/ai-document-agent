package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionRunner;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.conversation.application.ConversationMemoryService;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ExecutionRunnerTest {
    @Test
    void resumeAfterAgentResultCheckpointFinalizesWithoutCallingAgentAgain() {
        ExecutionStore store = mock(ExecutionStore.class);
        AgentRouter router = mock(AgentRouter.class);
        AgentExecution execution = AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                new JsonMapper().valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        LocalDateTime now = LocalDateTime.now();
        execution.claim(now, now.plusMinutes(2), false);
        execution.checkpoint(new JsonMapper().valueToTree(Map.of("answer", "saved")));
        execution.recover(now.plusMinutes(3));
        String token = execution.claim(now.plusMinutes(4), now.plusMinutes(6), true);
        when(store.get(execution.getExecutionId(), 7L)).thenReturn(execution);
        ExecutionRunner runner = new ExecutionRunner(store, router, event -> { },
                mock(ConversationRepository.class), mock(ObjectProvider.class),
                new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties());
        runner.runClaimed(execution.getExecutionId(), 7L, token);
        verify(store).complete(execution.getExecutionId(), 7L, token);
        verify(router, never()).execute(any(), any());
    }
}
