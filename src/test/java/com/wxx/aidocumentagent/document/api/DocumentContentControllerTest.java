package com.wxx.aidocumentagent.document.api;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.document.api.dto.DocumentChunkPageResponse;
import com.wxx.aidocumentagent.document.api.dto.DocumentChunkResponse;
import com.wxx.aidocumentagent.document.application.DocumentContentService;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentContentControllerTest {
    private final DocumentContentService service = mock(DocumentContentService.class);
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DocumentContentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilter(new TraceIdFilter()).build();
    }

    @Test
    void chunksUseBoundedDefaultsAndExplicitPublicFields() throws Exception {
        when(service.chunks(8, 15, 0, 50)).thenReturn(new DocumentChunkPageResponse(
                List.of(new DocumentChunkResponse(500L, 0, "正文", null, null, null)), 0, 50, 1125, 23));
        mvc.perform(get("/api/v1/knowledge-bases/8/documents/15/chunks"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.totalElements").value(1125))
                .andExpect(jsonPath("$.data.content[0].chunkId").value(500))
                .andExpect(jsonPath("$.data.content[0].content").value("正文"))
                .andExpect(jsonPath("$.data.content[0].metadataJson").doesNotExist());
        verify(service).chunks(8, 15, 0, 50);
    }

    @Test
    void invalidPagingAndPathIdsAreRejectedBeforeCallingTheService() throws Exception {
        for (String[] param : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"}, new String[]{"size", "101"})) {
            mvc.perform(get("/api/v1/knowledge-bases/8/documents/15/chunks").param(param[0], param[1]))
                    .andExpect(status().isBadRequest());
        }
        for (String endpoint : List.of("chunks", "download")) {
            mvc.perform(get("/api/v1/knowledge-bases/0/documents/15/" + endpoint)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/knowledge-bases/8/documents/0/" + endpoint)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void downloadsPreserveBytesNamesAndTypesAndCloseStreams() throws Exception {
        byte[] bytes = "原始字节".getBytes(StandardCharsets.UTF_8);
        for (String[] format : List.of(new String[]{"txt", "text/plain"},
                new String[]{"pdf", "application/pdf"}, new String[]{"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"})) {
            var closed = new AtomicBoolean();
            var input = new ByteArrayInputStream(bytes) { @Override public void close() { closed.set(true); } };
            String filename = "原书 \"校本\"." + format[0];
            when(service.download(8, 15)).thenReturn(new DocumentContentService.Download(filename, format[0], bytes.length, input));
            var result = mvc.perform(get("/api/v1/knowledge-bases/8/documents/15/download")
                            .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_PDF))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                    .andExpect(header().string("Content-Type", format[1]))
                    .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Cache-Control", "private, no-store")).andReturn();
            assertThat(ContentDisposition.parse(result.getResponse().getHeader("Content-Disposition")).getFilename()).isEqualTo(filename);
            assertThat(closed.get()).isTrue();
        }
    }

    @Test
    void utf16OriginalIsNotRelabeledAsUtf8() throws Exception {
        byte[] bytes = new byte[]{(byte) 0xff, (byte) 0xfe, 65, 0};
        when(service.download(8, 15)).thenReturn(new DocumentContentService.Download("原文.txt", "txt", bytes.length,
                new ByteArrayInputStream(bytes)));
        mvc.perform(get("/api/v1/knowledge-bases/8/documents/15/download").accept(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Type", "text/plain"));
    }

    @Test
    void binaryClientsStillReceiveCorrectJsonErrorsBeforeAnyFileBytes() throws Exception {
        when(service.download(8, 15)).thenThrow(new BusinessException(DocumentErrorCode.FILE_NOT_FOUND));
        mvc.perform(get("/api/v1/knowledge-bases/8/documents/15/download")
                        .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("DOCUMENT_FILE_NOT_FOUND"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }
}
