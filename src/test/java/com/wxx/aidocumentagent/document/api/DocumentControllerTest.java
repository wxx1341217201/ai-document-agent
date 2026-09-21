package com.wxx.aidocumentagent.document.api;

import java.time.LocalDateTime;
import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.document.api.dto.DocumentPageResponse;
import com.wxx.aidocumentagent.document.api.dto.DocumentResponse;
import com.wxx.aidocumentagent.document.application.DocumentApplicationService;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerTest {

    private DocumentApplicationService documentApplicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentApplicationService = mock(DocumentApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentApplicationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new TraceIdFilter())
                .build();
    }

    @Test
    void 上传多部件内容时使用统一响应包络() throws Exception {
        when(documentApplicationService.upload(anyLong(), any())).thenReturn(documentResponse());
        MockMultipartFile file = new MockMultipartFile("file", "guide.pdf", MediaType.APPLICATION_PDF_VALUE,
                "%PDF-1.7".getBytes());

        mockMvc.perform(multipart("/api/v1/knowledge-bases/7/documents").file(file)
                        .header(TraceIdFilter.TRACE_ID_HEADER, "document-upload"))
                .andExpect(status().isCreated())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "document-upload"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(23))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist());
    }

    @Test
    void 在指定知识库内列出文档() throws Exception {
        when(documentApplicationService.list(7L, 1, 20))
                .thenReturn(new DocumentPageResponse(List.of(documentResponse()), 1, 20, 1, 1));

        mockMvc.perform(get("/api/v1/knowledge-bases/7/documents").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(23))
                .andExpect(jsonPath("$.data.page").value(1));

        verify(documentApplicationService).list(7L, 1, 20);
    }

    @Test
    void 拒绝超过上限的每页数量() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases/7/documents").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data[0].field").value("size"));
    }

    @Test
    void 跨知识库查询返回文档不存在() throws Exception {
        when(documentApplicationService.getById(7L, 23L))
                .thenThrow(new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/knowledge-bases/7/documents/23"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void 删除指定知识库内的文档() throws Exception {
        doNothing().when(documentApplicationService).delete(7L, 23L);

        mockMvc.perform(delete("/api/v1/knowledge-bases/7/documents/23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(documentApplicationService).delete(7L, 23L);
    }

    private DocumentResponse documentResponse() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 20, 10, 0);
        return new DocumentResponse(23L, 7L, "guide.pdf", "application/pdf", "pdf", 8L,
                "a".repeat(64), DocumentStatus.UPLOADED, null, null, timestamp, timestamp);
    }
}
