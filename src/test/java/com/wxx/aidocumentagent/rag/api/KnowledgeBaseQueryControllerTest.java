package com.wxx.aidocumentagent.rag.api;

import java.util.List;

import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.rag.api.dto.RagQueryResponse;
import com.wxx.aidocumentagent.rag.api.dto.RagRetrievalResponse;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseQueryControllerTest {

    private RagQueryApplicationService applicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        applicationService = mock(RagQueryApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseQueryController(applicationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new TraceIdFilter())
                .build();
    }

    @Test
    void 非流式查询只做协议适配并返回统一响应包络() throws Exception {
        when(applicationService.query(eq(7L), any())).thenReturn(new RagQueryResponse("答案。[C1]", List.of(),
                new RagRetrievalResponse(false, 1)));

        mockMvc.perform(post("/api/v1/knowledge-bases/7/query")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "rag-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" 系统如何处理重复消息？ \",\"topK\":8,\"rerank\":true,\"stream\":false}"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "rag-query"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.answer").value("答案。[C1]"))
                .andExpect(jsonPath("$.data.retrieval.degraded").value(false))
                .andExpect(jsonPath("$.traceId").value("rag-query"));

        ArgumentCaptor<RagQueryCommand> command = ArgumentCaptor.forClass(RagQueryCommand.class);
        verify(applicationService).query(eq(7L), command.capture());
        assertThat(command.getValue().question()).isEqualTo("系统如何处理重复消息？");
        assertThat(command.getValue().topK()).isEqualTo(8);
        assertThat(command.getValue().rerank()).isTrue();
    }

    @Test
    void 未定义SSE契约时拒绝streamTrue避免静默忽略请求语义() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases/7/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\",\"stream\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data[0].field").value("stream"));
    }
}
