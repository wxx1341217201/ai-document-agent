package com.wxx.aidocumentagent.progress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecution;
import com.wxx.aidocumentagent.agent.execution.persistence.AgentExecutionRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProgressWebSocketHandlerTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void connectSubscribeOrderedEventsAndReconnectUsesRestStatus() throws Exception {
        ProgressProperties settings = new ProgressProperties();
        InMemorySequences sequences = new InMemorySequences();
        ProgressWebSocketBroker broker = new ProgressWebSocketBroker(Runnable::run, mapper, settings);
        AgentExecution execution = AgentExecution.create("key", "hash", 7, null, AgentMode.REACT, "question",
                mapper.valueToTree(Map.of()), java.time.LocalDateTime.now().plusHours(1));
        AgentExecutionRepository repository = mock(AgentExecutionRepository.class);
        when(repository.findByExecutionIdAndKnowledgeBaseId(execution.getExecutionId(), 7))
                .thenReturn(Optional.of(execution));
        ProgressSubscriptionService service = new ProgressSubscriptionService(
                (subscriber, request) -> subscriber.sharedTokenAuthenticated(), repository,
                mock(DocumentRepository.class), mock(DocumentIngestionJobRepository.class));
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(service, sequences, broker, mapper);
        ProgressEmitter emitter = new ProgressEmitter(sequences, broker);
        ProgressStream stream = ProgressStream.agent(7, execution.getExecutionId());
        List<String> firstMessages = new ArrayList<>();
        WebSocketSession first = session("first", true, firstMessages);
        handler.afterConnectionEstablished(first);
        assertThat(node(firstMessages.getFirst()).get("type").asText()).isEqualTo("CONNECTED");
        handler.handleTextMessage(first, subscribe(execution.getExecutionId()));
        JsonNode acknowledgement = node(firstMessages.get(1));
        assertThat(acknowledgement.get("type").asText()).isEqualTo("SUBSCRIBED");
        assertThat(acknowledgement.get("latestSequence").longValue()).isZero();
        assertThat(acknowledgement.get("statusUrl").asText())
                .isEqualTo("/api/v1/agent/executions/" + execution.getExecutionId() + "?knowledgeBaseId=7");

        emitter.afterCommit(stream, ProgressEventType.EXECUTION_STARTED,
                ProgressPayload.agent(null, "RUNNING", "执行已开始", null, null));
        emitter.afterCommit(stream, ProgressEventType.STEP_COMPLETED,
                ProgressPayload.agent("S1", "SUCCEEDED", "步骤状态已更新", null, null));
        assertThat(node(firstMessages.get(2)).get("sequence").longValue()).isEqualTo(1);
        assertThat(node(firstMessages.get(3)).get("sequence").longValue()).isEqualTo(2);
        assertThat(node(firstMessages.get(3)).get("type").asText()).isEqualTo("STEP_COMPLETED");
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);

        emitter.afterCommit(stream, ProgressEventType.EXECUTION_COMPLETED,
                ProgressPayload.agent(null, "SUCCEEDED", "执行已完成", null, null));
        assertThat(firstMessages).hasSize(4);
        List<String> secondMessages = new ArrayList<>();
        WebSocketSession second = session("second", true, secondMessages);
        handler.afterConnectionEstablished(second);
        handler.handleTextMessage(second, subscribe(execution.getExecutionId()));
        assertThat(node(secondMessages.get(1)).get("latestSequence").longValue()).isEqualTo(3);
        assertThat(secondMessages).hasSize(2); // No event replay; statusUrl is the recovery path.
    }

    @Test
    void unauthorizedSubscriptionIsRejectedWithoutLeakingExistence() throws Exception {
        ProgressWebSocketBroker broker = new ProgressWebSocketBroker(Runnable::run, mapper, new ProgressProperties());
        AgentExecutionRepository repository = mock(AgentExecutionRepository.class);
        ProgressSubscriptionService service = new ProgressSubscriptionService(
                (subscriber, request) -> subscriber.sharedTokenAuthenticated(), repository,
                mock(DocumentRepository.class), mock(DocumentIngestionJobRepository.class));
        ProgressWebSocketHandler handler = new ProgressWebSocketHandler(service, new InMemorySequences(), broker, mapper);
        List<String> messages = new ArrayList<>();
        WebSocketSession socket = session("unauthorized", false, messages);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, subscribe("12345678-1234-1234-1234-123456789abc"));
        assertThat(node(messages.get(1)).get("code").asText()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(repository);
    }

    @Test
    void slowClientIsClosedWhenItsQueueFills() throws Exception {
        ProgressProperties settings = new ProgressProperties();
        settings.setPendingMessagesPerSession(2);
        List<Runnable> delayedSends = new ArrayList<>();
        ProgressWebSocketBroker broker = new ProgressWebSocketBroker(delayedSends::add, mapper, settings);
        WebSocketSession socket = session("slow", true, new ArrayList<>());
        assertThat(broker.connect(socket)).isTrue();
        ProgressStream stream = ProgressStream.agent(7, "12345678-1234-1234-1234-123456789abc");
        broker.subscribe(socket, stream);
        broker.sendControl(socket, Map.of("type", "CONNECTED"));
        broker.publish(stream, event(stream, 1));
        broker.publish(stream, event(stream, 2));
        verify(socket).close(any(CloseStatus.class));
        assertThat(delayedSends).hasSize(1);
    }

    private ProgressEvent event(ProgressStream stream, long sequence) {
        return new ProgressEvent(java.util.UUID.randomUUID(), stream.executionId(), sequence,
                ProgressEventType.STEP_COMPLETED, java.time.Instant.now(),
                ProgressPayload.agent("S1", "SUCCEEDED", "步骤状态已更新", null, null));
    }

    private WebSocketSession session(String id, boolean authenticated, List<String> sent) throws IOException {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(new HashMap<>(Map.of(
                ProgressHandshakeInterceptor.AUTHENTICATED, authenticated)));
        doAnswer(call -> { sent.add(((WebSocketMessage<?>) call.getArgument(0)).getPayload().toString()); return null; })
                .when(socket).sendMessage(any(WebSocketMessage.class));
        return socket;
    }

    private TextMessage subscribe(String id) {
        return new TextMessage("{\"action\":\"SUBSCRIBE\",\"resource\":\"EXECUTION\","
                + "\"knowledgeBaseId\":7,\"executionId\":\"" + id + "\"}");
    }

    private JsonNode node(String json) { return mapper.readTree(json); }

    private static class InMemorySequences implements ProgressSequenceStore {
        private final AtomicLong value = new AtomicLong();
        @Override public long next(ProgressStream stream) { return value.incrementAndGet(); }
        @Override public long current(ProgressStream stream) { return value.get(); }
    }
}
