package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionApplicationService;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionRecoveryScanner;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionRecoveryScannerTest {
    @Test
    void startupScansScopedStaleAndPendingExecutions() {
        ExecutionStore store = mock(ExecutionStore.class);
        ExecutionApplicationService service = mock(ExecutionApplicationService.class);
        AgentExecution execution = AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                new JsonMapper().valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        when(store.stale(any(), any(Pageable.class))).thenReturn(List.of(execution));
        when(store.expiredWaiting(any(), any(Pageable.class))).thenReturn(List.of());
        when(store.pending(any(Pageable.class))).thenReturn(List.of(execution));
        new ExecutionRecoveryScanner(store, service).onReady();
        verify(store).recover(eq(execution.getExecutionId()), eq(7L), any());
        verify(service).dispatchPending(execution.getExecutionId(), 7L);
    }
}
