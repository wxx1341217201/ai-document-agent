package com.wxx.aidocumentagent.progress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProgressWebSocketEndpointTest {
    private static final String TEST_TOKEN = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void progressProperties(DynamicPropertyRegistry registry) {
        registry.add("app.progress.enabled", () -> true);
        registry.add("app.progress.subscription-token", () -> TEST_TOKEN);
    }

    @LocalServerPort private int port;
    @Autowired private ProgressEmitter emitter;
    @MockitoBean private JdbcProgressSequenceStore sequences;
    @MockitoBean private AgentExecutionRepository executions;
    @MockitoBean private KnowledgeBaseRepository knowledgeBases;
    @MockitoBean private DocumentRepository documents;
    @MockitoBean private DocumentChunkRepository chunks;
    @MockitoBean private DocumentIngestionJobRepository jobs;
    @MockitoBean private DocumentBatchTaskRepository batches;
    @MockitoBean private DocumentIngestionOutboxEventRepository outbox;

    @Test
    void actualWebSocketHandshakeAndScopedSubscribe() throws Exception {
        JsonMapper mapper = new JsonMapper();
        AgentExecution execution = AgentExecution.create("key", "hash", 7, null, AgentMode.REACT, "question",
                mapper.valueToTree(Map.of()), LocalDateTime.now().plusHours(1));
        when(executions.findByExecutionIdAndKnowledgeBaseId(execution.getExecutionId(), 7))
                .thenReturn(Optional.of(execution));
        ProgressStream stream = ProgressStream.agent(7, execution.getExecutionId());
        when(sequences.current(stream)).thenReturn(0L);
        when(sequences.next(stream)).thenReturn(1L);
        try (HttpClient client = HttpClient.newHttpClient()) {
            Collector authorized = new Collector();
            WebSocket socket = client.newWebSocketBuilder().header("Authorization", "Bearer " + TEST_TOKEN)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/progress"), authorized)
                    .get(5, TimeUnit.SECONDS);
            assertThat(authorized.next()).contains("CONNECTED");
            socket.sendText(request(execution.getExecutionId()), true).get(5, TimeUnit.SECONDS);
            assertThat(authorized.next()).contains("SUBSCRIBED").contains("statusUrl");
            emitter.afterCommit(stream, ProgressEventType.EXECUTION_COMPLETED,
                    ProgressPayload.agent(null, "SUCCEEDED", "执行已完成", null, null));
            assertThat(authorized.next()).contains("EXECUTION_COMPLETED").contains("\"sequence\":1");
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

            Collector denied = new Collector();
            WebSocket noToken = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/progress"), denied)
                    .get(5, TimeUnit.SECONDS);
            assertThat(denied.next()).contains("CONNECTED");
            noToken.sendText(request(execution.getExecutionId()), true).get(5, TimeUnit.SECONDS);
            assertThat(denied.next()).contains("SUBSCRIPTION_REJECTED").contains("FORBIDDEN");
            noToken.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    private String request(String executionId) {
        return "{\"action\":\"SUBSCRIBE\",\"resource\":\"EXECUTION\",\"knowledgeBaseId\":7,"
                + "\"executionId\":\"" + executionId + "\"}";
    }

    private static final class Collector implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder current = new StringBuilder();

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket,
                                                                         CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                messages.add(current.toString());
                current.setLength(0);
            }
            socket.request(1);
            return null;
        }

        String next() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            return message;
        }
    }
}
