package com.wxx.aidocumentagent.recovery.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkObservation.*;

/** Bounded loopback HTTP/WS calls; only safe reads are polled through transient outages. */
final class RecoveryBenchmarkClient {
    private final ObjectMapper mapper;
    private final RecoveryFaultEnvironment environment;
    private final HttpClient http;
    RecoveryBenchmarkClient(ObjectMapper mapper, RecoveryFaultEnvironment environment) {
        this(mapper, environment, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }
    RecoveryBenchmarkClient(ObjectMapper mapper, RecoveryFaultEnvironment environment, HttpClient http) {
        this.mapper = mapper; this.environment = environment; this.http = http;
    }
    JsonNode get(String path) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return call("GET", path, null, null); }
            catch (HttpFailure e) {
                if (attempt >= 2 || !List.of(502, 503, 504).contains(e.status)) throw e;
            }
            catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
                if (attempt >= 2) throw e;
            }
            Thread.sleep(150);
        }
    }
    JsonNode post(String path, Object body, String requestKey) throws Exception { return call("POST", path, body, requestKey); }
    private JsonNode call(String method, String path, Object body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(environment.baseUrl() + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + environment.token());
        if (key != null) builder.header("Idempotency-Key", key);
        if ("POST".equals(method)) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : mapper.writeValueAsString(body)));
        else builder.GET();
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String code = null;
            try {
                String candidate = mapper.readTree(response.body()).path("code").asText();
                if (candidate.matches("[A-Z][A-Z0-9_]{0,63}")) code = candidate;
            } catch (RuntimeException ignored) { /* raw server bodies are never logged */ }
            throw new HttpFailure(response.statusCode(), code);
        }
        JsonNode root = mapper.readTree(response.body());
        if (root.has("code")) {
            if (!"SUCCESS".equals(root.path("code").asText())) throw new IOException("API_RESPONSE_NOT_SUCCESS");
            return root.path("data");
        }
        return root;
    }
    Snapshot snapshot(long id, long kb) throws Exception { return mapper.treeToValue(get("/benchmark/recovery/snapshot/" + id + "?knowledgeBaseId=" + kb), Snapshot.class); }
    void verifyRestHistory(Snapshot expected) throws Exception {
        // First prove the scope through the explicitly scoped fixture service, then read the actual production REST endpoint.
        Snapshot scoped = snapshot(expected.conversationId(), expected.knowledgeBaseId());
        if (scoped.knowledgeBaseId() != expected.knowledgeBaseId()) throw new IOException("REST_SCOPE_MISMATCH");
        var messages = new java.util.ArrayList<Message>(); int page = 0; JsonNode result;
        do {
            result = get("/api/v1/conversations/" + expected.conversationId() + "/messages?page=" + page + "&size=100");
            for (JsonNode item : result.path("content")) messages.add(Message.from(mapper.treeToValue(item,
                    com.wxx.aidocumentagent.conversation.api.dto.ConversationMessageResponse.class), mapper));
            page++;
        } while (page < result.path("totalPages").asInt());
        if (!messages.equals(expected.messages())) throw new IOException("REST_HISTORY_MISMATCH");
    }
    boolean rejectsCrossKnowledgeBase(long id) throws Exception {
        try { snapshot(id, environment.decoyKnowledgeBaseId()); return false; }
        catch (HttpFailure e) { return e.status == 404; }
    }
    JsonNode poll(String path, Predicate<JsonNode> done, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        // Time AND attempt bounds apply. Non-retryable HTTP codes fail immediately.
        for (int attempt = 0; attempt < 600 && System.nanoTime() < deadline; attempt++) {
            try { JsonNode result = call("GET", path, null, null); if (done.test(result)) return result; }
            catch (HttpFailure e) { if (!List.of(502, 503, 504).contains(e.status)) throw e; }
            catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) { /* bounded transient read retry */ }
            Thread.sleep(150);
        }
        throw new IOException("RECOVERY_POLL_TIMEOUT");
    }
    SocketSubscription subscribe(String executionId, long kb) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try { return subscribeOnce(executionId, kb, attempt); }
            catch (Exception e) {
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); throw e; }
                if (attempt >= 3 || !transientSocketFailure(e)) throw e;
            }
            Thread.sleep(150);
        }
    }
    private static boolean transientSocketFailure(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof java.util.concurrent.CompletionException)
                && error.getCause() != null) error = error.getCause();
        if (error instanceof WebSocketHandshakeException handshake) {
            return List.of(502, 503, 504).contains(handshake.getResponse().statusCode());
        }
        if (error instanceof TimeoutException || error instanceof java.net.http.HttpTimeoutException) return true;
        if (!(error instanceof IOException)) return false;
        return error.getMessage() == null || !List.of("WS_SUBSCRIPTION_REJECTED", "WS_SUBSCRIPTION_SCOPE_MISMATCH", "WS_PROTOCOL_ERROR", "WS_MESSAGE_TOO_LARGE")
                .contains(error.getMessage());
    }
    private SocketSubscription subscribeOnce(String executionId, long kb, int attempt) throws Exception {
        var listener = new SubscriptionListener(mapper);
        var connecting = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + environment.token())
                .buildAsync(URI.create(environment.baseUrl().replace("http://", "ws://") + "/ws/progress"), listener);
        WebSocket socket;
        try { socket = connecting.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { connecting.thenAccept(WebSocket::abort); throw e; }
        try {
            socket.sendText(mapper.writeValueAsString(Map.of("action", "SUBSCRIBE", "resource", "EXECUTION", "knowledgeBaseId", kb,
                    "executionId", executionId)), true).get(5, TimeUnit.SECONDS);
            JsonNode ack = listener.subscribed.get(5, TimeUnit.SECONDS);
            if (!executionId.equals(ack.path("executionId").asText()) || ack.path("knowledgeBaseId").asLong() != kb
                    || !("/api/v1/agent/executions/" + executionId + "?knowledgeBaseId=" + kb).equals(ack.path("statusUrl").asText())) {
                throw new IOException("WS_SUBSCRIPTION_SCOPE_MISMATCH");
            }
            return new SocketSubscription(socket, ack.path("latestSequence").asLong(), ack.path("statusUrl").asText(), attempt);
        } catch (Exception e) { socket.abort(); throw e; }
    }
    record SocketSubscription(WebSocket socket, long latestSequence, String statusUrl, int connectionAttempts) implements AutoCloseable {
        @Override public void close() { socket.abort(); }
    }
    private static final class SubscriptionListener implements WebSocket.Listener {
        private final ObjectMapper mapper;
        private final StringBuilder message = new StringBuilder();
        private final CompletableFuture<JsonNode> subscribed = new CompletableFuture<>();
        SubscriptionListener(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            message.append(text);
            if (message.length() > 65536) { subscribed.completeExceptionally(new IOException("WS_MESSAGE_TOO_LARGE")); socket.abort(); return null; }
            if (last) {
                try {
                    JsonNode node = mapper.readTree(message.toString());
                    if ("SUBSCRIBED".equals(node.path("type").asText())) subscribed.complete(node);
                    if ("SUBSCRIPTION_REJECTED".equals(node.path("type").asText())) subscribed.completeExceptionally(new IOException("WS_SUBSCRIPTION_REJECTED"));
                } catch (RuntimeException e) { subscribed.completeExceptionally(new IOException("WS_PROTOCOL_ERROR")); }
                message.setLength(0);
            }
            socket.request(1); return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { subscribed.completeExceptionally(new IOException("WS_CONNECTION_ERROR")); }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            subscribed.completeExceptionally(new IOException("WS_CONNECTION_CLOSED")); return null;
        }
    }
    static final class HttpFailure extends IOException {
        final int status;
        final String apiCode;
        HttpFailure(int status, String apiCode) { super("HTTP_" + status); this.status = status; this.apiCode = apiCode; }
    }
}
