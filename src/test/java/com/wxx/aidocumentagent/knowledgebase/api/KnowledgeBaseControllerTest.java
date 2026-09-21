package com.wxx.aidocumentagent.knowledgebase.api;

import java.time.LocalDateTime;
import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.knowledgebase.api.dto.CreateKnowledgeBaseRequest;
import com.wxx.aidocumentagent.knowledgebase.api.dto.KnowledgeBasePageResponse;
import com.wxx.aidocumentagent.knowledgebase.api.dto.KnowledgeBaseResponse;
import com.wxx.aidocumentagent.knowledgebase.application.KnowledgeBaseApplicationService;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseControllerTest {

    private KnowledgeBaseApplicationService knowledgeBaseApplicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        knowledgeBaseApplicationService = mock(KnowledgeBaseApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(knowledgeBaseApplicationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new TraceIdFilter())
                .build();
    }

    @Test
    void 创建知识库时使用统一响应包络() throws Exception {
        when(knowledgeBaseApplicationService.create(any())).thenReturn(knowledgeBaseResponse());

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "knowledge-base-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  产品文档  \",\"description\":\"内部资料\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "knowledge-base-create"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.name").value("产品文档"))
                .andExpect(jsonPath("$.traceId").value("knowledge-base-create"));

        ArgumentCaptor<CreateKnowledgeBaseRequest> request = ArgumentCaptor.forClass(CreateKnowledgeBaseRequest.class);
        verify(knowledgeBaseApplicationService).create(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().name()).isEqualTo("产品文档");
    }

    @Test
    void 去除空白后的空名称会被拒绝() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data[0].field").value("name"));
    }

    @Test
    void 返回存在知识库的详情() throws Exception {
        when(knowledgeBaseApplicationService.getById(7L)).thenReturn(knowledgeBaseResponse());

        mockMvc.perform(get("/api/v1/knowledge-bases/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.description").value("内部资料"));
    }

    @Test
    void 返回知识库专属的未找到响应() throws Exception {
        when(knowledgeBaseApplicationService.getById(99L)).thenThrow(
                new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/knowledge-bases/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("知识库不存在"));
    }

    @Test
    void 使用受限分页参数列出知识库() throws Exception {
        KnowledgeBasePageResponse page = new KnowledgeBasePageResponse(List.of(knowledgeBaseResponse()), 1, 50, 1, 1);
        when(knowledgeBaseApplicationService.list(1, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/knowledge-bases").param("page", "1").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.content[0].id").value(7));

        verify(knowledgeBaseApplicationService).list(1, 50);
    }

    @Test
    void 超过上限的每页数量会被拒绝() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data[0].field").value("size"));
    }

    @Test
    void 更新知识库() throws Exception {
        when(knowledgeBaseApplicationService.update(eq(7L), any())).thenReturn(knowledgeBaseResponse());

        mockMvc.perform(put("/api/v1/knowledge-bases/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"产品文档\",\"description\":\"更新后说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("产品文档"));
    }

    @Test
    void 重复名称返回稳定冲突响应() throws Exception {
        when(knowledgeBaseApplicationService.create(any())).thenThrow(
                new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT));

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重复名称\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NAME_CONFLICT"))
                .andExpect(jsonPath("$.message").value("知识库名称已存在"));
    }

    @Test
    void 删除知识库时使用统一响应包络() throws Exception {
        doNothing().when(knowledgeBaseApplicationService).delete(7L);

        mockMvc.perform(delete("/api/v1/knowledge-bases/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(knowledgeBaseApplicationService).delete(7L);
    }

    private KnowledgeBaseResponse knowledgeBaseResponse() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 20, 10, 0);
        return new KnowledgeBaseResponse(7L, "产品文档", "内部资料", timestamp, timestamp);
    }
}
