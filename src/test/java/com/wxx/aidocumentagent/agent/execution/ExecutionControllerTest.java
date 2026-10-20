package com.wxx.aidocumentagent.agent.execution;

import java.time.LocalDateTime;
import java.util.List;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.api.ExecutionController;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionResponse;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionPageResponse;
import com.wxx.aidocumentagent.agent.execution.api.dto.ExecutionSummaryResponse;
import com.wxx.aidocumentagent.agent.execution.application.ExecutionApplicationService;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionControllerTest {
    private ExecutionApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(ExecutionApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExecutionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilter(new TraceIdFilter()).build();
    }

    @Test
    void createIsAcceptedAndIdempotencyConflictIs409() throws Exception {
        when(service.create(eq("same-key"), any())).thenReturn(new ExecutionApplicationService.Created(response(), true))
                .thenThrow(new BusinessException(ExecutionErrorCode.REQUEST_KEY_CONFLICT));
        String body = "{\"knowledgeBaseId\":7,\"agentMode\":\"REACT\",\"question\":\"question\"}";
        mvc.perform(post("/api/v1/agent/executions").header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.executionId").value("12345678-1234-1234-1234-123456789abc"));
        mvc.perform(post("/api/v1/agent/executions").header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_KEY_CONFLICT"));
    }

    @Test
    void statusRequiresKnowledgeBaseScope() throws Exception {
        mvc.perform(get("/api/v1/agent/executions/12345678-1234-1234-1234-123456789abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listDefaultsToTwentySummariesWithoutExecutionSecrets() throws Exception {
        var item = new ExecutionSummaryResponse("12345678-1234-1234-1234-123456789abc", null, 7,
                AgentMode.REACT, ExecutionStatus.SUCCEEDED, "finalize", null, LocalDateTime.now());
        when(service.list(0, 20, null, null)).thenReturn(new ExecutionPageResponse(List.of(item), 0, 20, 1, 1));
        mvc.perform(get("/api/v1/agent/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].knowledgeBaseId").value(7))
                .andExpect(jsonPath("$.data.content[0].question").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].runToken").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].resultJson").doesNotExist())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void listAcceptsCombinedFiltersAndRejectsInvalidPaginationAndEnums() throws Exception {
        when(service.list(1, 5, ExecutionStatus.PAUSED, AgentMode.REACT))
                .thenReturn(new ExecutionPageResponse(List.of(), 1, 5, 0, 0));
        mvc.perform(get("/api/v1/agent/executions").param("page", "1").param("size", "5")
                        .param("status", "PAUSED").param("agentMode", "REACT"))
                .andExpect(status().isOk());
        verify(service).list(1, 5, ExecutionStatus.PAUSED, AgentMode.REACT);
        for (String[] param : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"size", "101"}, new String[]{"status", "BROKEN"}, new String[]{"agentMode", "RAG"})) {
            mvc.perform(get("/api/v1/agent/executions").param(param[0], param[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/v1/agent/executions"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    private ExecutionResponse response() {
        LocalDateTime now = LocalDateTime.now();
        return new ExecutionResponse("12345678-1234-1234-1234-123456789abc", 7L, null, AgentMode.REACT,
                ExecutionStatus.PENDING, "agent_run", 0L, now.plusHours(1), false, false, null, null,
                List.of(), List.of(), now, now);
    }
}
