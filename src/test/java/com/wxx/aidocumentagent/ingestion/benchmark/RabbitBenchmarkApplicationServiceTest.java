package com.wxx.aidocumentagent.ingestion.benchmark;

import java.io.ByteArrayInputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.wxx.aidocumentagent.document.api.dto.DocumentResponse;
import com.wxx.aidocumentagent.document.application.DocumentApplicationService;
import com.wxx.aidocumentagent.document.application.UploadDocumentCommand;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBase;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RabbitBenchmarkApplicationServiceTest {
    private final DocumentApplicationService documents = mock(DocumentApplicationService.class);
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final SerialBenchmarkIngestion serial = mock(SerialBenchmarkIngestion.class);
    private final RabbitBenchmarkMetrics metrics = new RabbitBenchmarkMetrics();
    private final UploadDocumentCommand command = new UploadDocumentCommand("sample.txt", "text/plain", 3,
            () -> new ByteArrayInputStream(new byte[] {1, 2, 3}));

    @BeforeEach
    void persistIdentities() {
        var knowledgeBaseIds = new AtomicLong(100);
        when(knowledgeBases.saveAndFlush(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", knowledgeBaseIds.incrementAndGet());
            return value;
        });
        var documentIds = new AtomicLong(10);
        when(documents.upload(anyLong(), any(UploadDocumentCommand.class))).thenAnswer(invocation -> {
            var response = mock(DocumentResponse.class);
            when(response.id()).thenReturn(documentIds.incrementAndGet());
            return response;
        });
    }

    @Test
    void 异步提交按阶段和轮次隔离且同轮共享知识库() throws Exception {
        var service = service("async");
        var first = service.submit(command, "doc-001", 2, 1.0);
        var second = service.submit(command, "doc-002", 2, 2.0);
        var third = service.submit(command, "doc-001", 3, 3.0);
        assertThat(first.knowledgeBaseId()).isEqualTo(second.knowledgeBaseId());
        assertThat(third.knowledgeBaseId()).isNotEqualTo(first.knowledgeBaseId());
        assertThat(metrics.documents().getFirst().arrivedAt).isEqualTo(1.0);
        assertThat(metrics.documents().getFirst().bytes).isEqualTo(3);
        metrics.documents().forEach(sample -> metrics.ready(sample.documentId, 4));

        assertThat(service.begin("measure")).containsEntry("phase", "measure");
        var measured = service.submit(command, "doc-001", 2, 5.0);
        assertThat(measured.knowledgeBaseId()).isNotEqualTo(first.knowledgeBaseId());
        var scopes = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBases, times(3)).saveAndFlush(scopes.capture());
        assertThat(scopes.getAllValues()).extracting(KnowledgeBase::getName)
                .containsExactly("m19e-warmup/2", "m19e-warmup/3", "m19e-measure/2");
        verifyNoInteractions(serial);
    }

    @Test
    void 未完成阶段与非法语料不能进入下一轮() throws Exception {
        var service = service("async");
        assertThatThrownBy(() -> service.submit(command, "invalid", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submit(command, "doc-001", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documents, knowledgeBases);
        service.submit(command, "doc-001", 0, 0);
        assertThatThrownBy(() -> service.begin("measure"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Undrained phase");
        assertThat(metrics.phase()).isEqualTo("warmup");
        assertThatThrownBy(() -> service.begin("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 同步处理失败后释放串行锁供下一个请求使用() throws Exception {
        var service = service("sync");
        doThrow(new IllegalStateException("worker failed")).doNothing().when(serial).process(anyLong(), anyLong());
        assertThatThrownBy(() -> service.submit(command, "doc-001", 0, 0))
                .isInstanceOf(IllegalStateException.class).hasMessage("worker failed");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var next = executor.submit(() -> service.submit(command, "doc-002", 0, 0));
            assertThat(next.get(3, TimeUnit.SECONDS).documentId()).isEqualTo(12);
        }
        finally {
            executor.shutdownNow();
        }
        verify(serial).process(11, 101);
        verify(serial).process(12, 101);
    }

    @Test
    void 上传HTTP契约和响应序列化后的确认计时保持一致() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RabbitBenchmarkController(service("async")))
                .addFilters(new RabbitBenchmarkRequestFilter("benchmark-token", metrics)).build();
        mvc.perform(multipart("/benchmark/documents")
                        .file(new MockMultipartFile("file", "sample.txt", "text/plain", new byte[] {1, 2, 3}))
                        .param("corpusId", "doc-001").param("round", "0")
                        .header("X-M19E-Token", "benchmark-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentId").value(11))
                .andExpect(jsonPath("$.knowledgeBaseId").value(101));
        var upload = ArgumentCaptor.forClass(UploadDocumentCommand.class);
        verify(documents).upload(eq(101L), upload.capture());
        assertThat(upload.getValue().originalName()).isEqualTo("sample.txt");
        assertThat(upload.getValue().content().getInputStream().readAllBytes()).containsExactly(1, 2, 3);
        var sample = metrics.documents().getFirst();
        assertThat(sample.ackAt).isNotNull().isGreaterThanOrEqualTo(sample.arrivedAt);
    }

    @Test
    void 默认应用中不装配基准控制器与应用服务() {
        new ApplicationContextRunner().withUserConfiguration(RabbitBenchmarkController.class,
                        RabbitBenchmarkApplicationService.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RabbitBenchmarkController.class)
                        .doesNotHaveBean(RabbitBenchmarkApplicationService.class));
    }

    private RabbitBenchmarkApplicationService service(String mode) {
        return new RabbitBenchmarkApplicationService(documents, knowledgeBases, serial, metrics, mock(JdbcTemplate.class),
                new MockEnvironment().withProperty("app.benchmark.rabbitmq.mode", mode), new ObjectMapper());
    }
}
