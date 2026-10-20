package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.api.dto.CreateExecutionRequest;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionApplicationService;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionRunner;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionStore;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionApplicationServiceTest {
    @Test
    void sameKeySameRequestReturnsSameExecutionButChangedBodyConflicts() {
        ExecutionStore store = mock(ExecutionStore.class);
        ThreadPoolTaskExecutor worker = mock(ThreadPoolTaskExecutor.class);
        KnowledgeBaseRepository bases = mock(KnowledgeBaseRepository.class);
        when(bases.existsById(7L)).thenReturn(true);
        AtomicReference<AgentExecution> saved = new AtomicReference<>();
        when(store.findByRequestKey("user-key", 7L)).thenAnswer(call -> Optional.ofNullable(saved.get()));
        when(store.create(eq("user-key"), any(), eq(7L), eq(null), eq(AgentMode.REACT), eq("question"), any()))
                .thenAnswer(call -> {
                    AgentExecution entity = AgentExecution.create("user-key", call.getArgument(1), 7L, null,
                            AgentMode.REACT, "question", new JsonMapper().valueToTree(Map.of()),
                            LocalDateTime.now().plusHours(1));
                    saved.set(entity);
                    return entity;
                });
        when(store.get(any(), eq(7L))).thenAnswer(call -> saved.get());
        when(store.steps(any(), eq(7L))).thenReturn(List.of());
        when(store.tools(any(), eq(7L))).thenReturn(List.of());
        ExecutionApplicationService service = new ExecutionApplicationService(store, mock(ExecutionRunner.class),
                worker, bases, mock(ConversationRepository.class), new JsonMapper());
        var request = new CreateExecutionRequest(7L, null, AgentMode.REACT, "question");
        var first = service.create("user-key", request);
        var repeated = service.create("user-key", request);
        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.response().executionId()).isEqualTo(first.response().executionId());
        assertThatThrownBy(() -> service.create("user-key",
                new CreateExecutionRequest(7L, null, AgentMode.REACT, "different")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ExecutionErrorCode.REQUEST_KEY_CONFLICT));
        assertThatThrownBy(() -> service.create("user-key",
                new CreateExecutionRequest(7L, null, AgentMode.REACT, " question ")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ExecutionErrorCode.REQUEST_KEY_CONFLICT));
        verify(worker).execute(any(Runnable.class));
    }

    @Test
    void resumeRequiresScopedClaimBeforeWorkerDispatch() {
        ExecutionStore store = mock(ExecutionStore.class);
        ThreadPoolTaskExecutor worker = mock(ThreadPoolTaskExecutor.class);
        when(store.claim("12345678-1234-1234-1234-123456789abc", 7L, true)).thenReturn("owner");
        AgentExecution entity = AgentExecution.create("key", "hash", 7L, null, AgentMode.REACT, "question",
                new JsonMapper().valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        when(store.get(any(), eq(7L))).thenReturn(entity);
        when(store.steps(any(), eq(7L))).thenReturn(List.of());
        when(store.tools(any(), eq(7L))).thenReturn(List.of());
        ExecutionApplicationService service = new ExecutionApplicationService(store, mock(ExecutionRunner.class),
                worker, mock(KnowledgeBaseRepository.class), mock(ConversationRepository.class), new JsonMapper());
        service.resume("12345678-1234-1234-1234-123456789abc", 7L);
        verify(store).claim("12345678-1234-1234-1234-123456789abc", 7L, true);
        verify(worker).execute(any(Runnable.class));
        verify(store, never()).claim(any(), eq(8L), eq(true));
    }
}
