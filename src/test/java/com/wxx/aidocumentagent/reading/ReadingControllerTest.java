package com.wxx.aidocumentagent.reading;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReadingControllerTest {
    private final ReadingStore store = mock(ReadingStore.class);
    private final ReadingService service = mock(ReadingService.class);
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ReadingController(store, service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilter(new TraceIdFilter()).build();
    }

    @Test void validatesExplicitScopeAndPagingBeforeServiceAccess() throws Exception {
        for (var body : List.of("{\"knowledgeBaseId\":8,\"goal\":\"阅读\",\"documentIds\":[]}",
                "{\"knowledgeBaseId\":8,\"goal\":\"\",\"documentIds\":[15]}",
                "{\"knowledgeBaseId\":8,\"goal\":\"阅读\",\"documentIds\":[null]}"))
            mvc.perform(post("/api/v1/reading/tasks").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for (var suffix : List.of("&page=-1", "&size=0", "&size=101"))
            mvc.perform(get("/api/v1/reading/tasks?knowledgeBaseId=8" + suffix)).andExpect(status().isBadRequest());
        verifyNoInteractions(store, service);
    }

    @Test void recoveryRequiresOriginalRoundAndReturnsConflictWithoutAutomaticRetry() throws Exception {
        mvc.perform(post("/api/v1/reading/tasks/task/resume?knowledgeBaseId=8")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}"))
                .andExpect(status().isBadRequest());
        when(store.action("task", 8, 4, 1, "resume")).thenThrow(new BusinessException(ReadingError.CONFLICT));
        mvc.perform(post("/api/v1/reading/tasks/task/resume?knowledgeBaseId=8")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4,\"expectedRoundNo\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ReadingError.CONFLICT.code()));
        verify(store, times(1)).action("task", 8, 4, 1, "resume");
    }

    @Test void exportContainsSavedTextAndSourceSnapshotsEvenWithoutLiveSources() throws Exception {
        var source = new Source("C1", 15, "历史原书.txt", "a".repeat(64), 100, 0, "b".repeat(64), 0, "已保存的历史原文", null, null, null);
        var note = new ReadingApi.Note("note", "task", "人工笔记 [C1]", 2, "result", null, List.of(source), "CHANGED", Instant.now());
        when(store.note("task", 8)).thenReturn(note);
        var response = mvc.perform(get("/api/v1/reading/tasks/task/note/export?knowledgeBaseId=8"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("人工笔记 [C1]", "已保存的历史原文", source.documentVersion());
        verifyNoInteractions(service);
    }
}
