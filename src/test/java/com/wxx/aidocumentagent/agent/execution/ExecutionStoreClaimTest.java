package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.ToolInvocationRepository;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionStoreClaimTest {
    @Test
    void resumeWithCommittedFinalResultPreservesSucceededAgentStep() {
        JsonMapper mapper = new JsonMapper();
        AgentExecution execution = AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                mapper.valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        LocalDateTime now = LocalDateTime.now();
        execution.claim(now, now.plusMinutes(2), false);
        execution.checkpoint(mapper.valueToTree(Map.of("answer", "durable final answer")));
        execution.recover(now.plusMinutes(3));
        var agentStep = AgentStepExecution.agentRun(execution.getExecutionId(), 7L);
        agentStep.markSucceeded();
        AgentExecutionRepository repository = mock(AgentExecutionRepository.class);
        AgentStepExecutionRepository steps = mock(AgentStepExecutionRepository.class);
        when(repository.findLocked(execution.getExecutionId(), 7L)).thenReturn(Optional.of(execution));
        when(steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(execution.getExecutionId(), 7L, "agent_run"))
                .thenReturn(Optional.of(agentStep));
        ExecutionStore store = new ExecutionStore(repository, steps, mock(ToolInvocationRepository.class),
                new ExecutionProperties(), mapper, new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties());
        String token = store.claim(execution.getExecutionId(), 7L, true);
        store.complete(execution.getExecutionId(), 7L, token);
        assertThat(agentStep.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void secondResumeLosesUnderScopedRowLockAndVersionedState() {
        JsonMapper mapper = new JsonMapper();
        AgentExecution execution = AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                mapper.valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        LocalDateTime now = LocalDateTime.now();
        execution.claim(now, now.plusMinutes(2), false);
        execution.recover(now.plusMinutes(3));
        AgentExecutionRepository repository = mock(AgentExecutionRepository.class);
        AgentStepExecutionRepository steps = mock(AgentStepExecutionRepository.class);
        when(repository.findLocked(execution.getExecutionId(), 7L)).thenReturn(Optional.of(execution));
        when(steps.findByExecutionIdAndKnowledgeBaseIdAndStepId(execution.getExecutionId(), 7L, "agent_run"))
                .thenReturn(Optional.of(AgentStepExecution.agentRun(execution.getExecutionId(), 7L)));
        ExecutionStore store = new ExecutionStore(repository, steps, mock(ToolInvocationRepository.class),
                new ExecutionProperties(), mapper, new RetrievalFirstProperties(), new ReActProperties(),
                new PlanProperties());
        String token = store.claim(execution.getExecutionId(), 7L, true);
        assertThat(execution.ownedBy(token)).isTrue();
        assertThatThrownBy(() -> store.claim(execution.getExecutionId(), 7L, true))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ExecutionErrorCode.STATE_CONFLICT));
    }
}
