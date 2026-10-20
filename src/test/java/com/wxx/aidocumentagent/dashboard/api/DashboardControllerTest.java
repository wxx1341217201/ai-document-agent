package com.wxx.aidocumentagent.dashboard.api;

import java.time.LocalDateTime;
import java.util.List;

import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.dashboard.api.dto.DashboardPageResponse;
import com.wxx.aidocumentagent.dashboard.api.dto.RecentDocumentResponse;
import com.wxx.aidocumentagent.dashboard.api.dto.RecentKnowledgeBaseResponse;
import com.wxx.aidocumentagent.dashboard.application.DashboardApplicationService;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerTest {

    private DashboardApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DashboardApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilter(new TraceIdFilter()).build();
    }

    @Test
    void 默认只取第一页五个文档并仅返回摘要字段() throws Exception {
        var document = new RecentDocumentResponse(23L, 7L, "guide.pdf", "pdf", DocumentStatus.READY,
                LocalDateTime.of(2026, 10, 19, 12, 0));
        when(service.documents(0, 5)).thenReturn(new DashboardPageResponse<>(List.of(document), 0, 5, 1844, 369));

        mvc.perform(get("/api/v1/dashboard/documents").header(TraceIdFilter.TRACE_ID_HEADER, "dashboard-page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("dashboard-page"))
                .andExpect(jsonPath("$.data.content[0].id").value(23))
                .andExpect(jsonPath("$.data.totalPages").value(369))
                .andExpect(jsonPath("$.data.content[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].sha256").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].errorMessage").doesNotExist());
        verify(service).documents(0, 5);
    }

    @Test
    void 知识库按请求读取第二页() throws Exception {
        var kb = new RecentKnowledgeBaseResponse(1L, "研究资料", null, LocalDateTime.now());
        when(service.knowledgeBases(1, 5)).thenReturn(new DashboardPageResponse<>(List.of(kb), 1, 5, 6, 2));

        mvc.perform(get("/api/v1/dashboard/knowledge-bases").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(6))
                .andExpect(jsonPath("$.data.content[0].name").value("研究资料"));
        verify(service).knowledgeBases(1, 5);
    }

    @Test
    void 非法页码和页容量在查询前被拒绝() throws Exception {
        for (String endpoint : List.of("documents", "knowledge-bases")) {
            mvc.perform(get("/api/v1/dashboard/" + endpoint).param("page", "-1"))
                    .andExpect(status().isBadRequest());
            for (String size : List.of("0", "101")) {
                mvc.perform(get("/api/v1/dashboard/" + endpoint).param("size", size))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(service);
    }
}
