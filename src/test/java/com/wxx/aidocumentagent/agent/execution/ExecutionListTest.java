package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.List;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentStepExecutionRepository;
import com.wxx.aidocumentagent.agent.execution.persistence.ToolInvocationRepository;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExecutionListTest {
    @Test
    void delegatesAllFilterCombinationsToBoundedProjectedQueriesAndKeepsTotals() {
        var repository = mock(AgentExecutionRepository.class);
        var steps = mock(AgentStepExecutionRepository.class);
        var tools = mock(ToolInvocationRepository.class);
        var store = new ExecutionStore(repository, steps, tools, new ExecutionProperties(), new JsonMapper(),
                new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties());
        var page = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "updatedAt", "executionId"));
        var item = mock(AgentExecutionRepository.Summary.class);
        when(item.getExecutionId()).thenReturn("12345678-1234-1234-1234-123456789abc");
        when(item.getKnowledgeBaseId()).thenReturn(8L);
        when(item.getAgentMode()).thenReturn(AgentMode.REACT);
        when(item.getStatus()).thenReturn(ExecutionStatus.SUCCEEDED);
        when(item.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 10, 20, 0, 0));
        var result = new PageImpl<>(List.of(item), page, 42);
        when(repository.findAllProjectedBy(page)).thenReturn(result);
        when(repository.findAllProjectedByStatus(ExecutionStatus.SUCCEEDED, page)).thenReturn(result);
        when(repository.findAllProjectedByAgentMode(AgentMode.REACT, page)).thenReturn(result);
        when(repository.findAllProjectedByStatusAndAgentMode(ExecutionStatus.SUCCEEDED, AgentMode.REACT, page)).thenReturn(result);
        for (ExecutionStatus status : new ExecutionStatus[]{null, ExecutionStatus.SUCCEEDED}) {
            for (AgentMode mode : new AgentMode[]{null, AgentMode.REACT}) {
                var response = store.list(1, 5, status, mode);
                assertThat(response.totalElements()).isEqualTo(42);
                assertThat(response.totalPages()).isEqualTo(9);
                assertThat(response.page()).isEqualTo(1);
                assertThat(response.content()).singleElement().satisfies(summary -> {
                    assertThat(summary.knowledgeBaseId()).isEqualTo(8);
                    assertThat(summary.agentMode()).isEqualTo(AgentMode.REACT);
                    assertThat(summary.startedAt()).isNull();
                });
            }
        }
        verify(repository).findAllProjectedBy(page);
        verify(repository).findAllProjectedByStatus(ExecutionStatus.SUCCEEDED, page);
        verify(repository).findAllProjectedByAgentMode(AgentMode.REACT, page);
        verify(repository).findAllProjectedByStatusAndAgentMode(ExecutionStatus.SUCCEEDED, AgentMode.REACT, page);
        verifyNoInteractions(steps, tools);
    }
}
