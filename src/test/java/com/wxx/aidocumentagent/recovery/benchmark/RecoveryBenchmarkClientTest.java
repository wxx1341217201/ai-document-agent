package com.wxx.aidocumentagent.recovery.benchmark;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryBenchmarkClientTest {
    private RecoveryBenchmarkClient client(HttpServer server) {
        var environment = mock(RecoveryFaultEnvironment.class);
        when(environment.baseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(environment.token()).thenReturn("test-only-token");
        return new RecoveryBenchmarkClient(new JsonMapper(), environment);
    }
    @Test void transientGetIsRetriedButEventuallyReturnsRealResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/state", exchange -> {
            boolean unavailable = calls.incrementAndGet() == 1;
            byte[] body = (unavailable ? "{}" : "{\"status\":\"SUCCEEDED\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(unavailable ? 503 : 200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            assertThat(client(server).poll("/state", n -> "SUCCEEDED".equals(n.path("status").asText()), Duration.ofSeconds(2)).path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(calls.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }
    @Test void missingAuthorityIsNotRetriedAndPreservesSafeDomainCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/missing", exchange -> {
            calls.incrementAndGet(); byte[] body = "{\"code\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            assertThatThrownBy(() -> client(server).poll("/missing", n -> true, Duration.ofSeconds(2)))
                    .isInstanceOf(RecoveryBenchmarkClient.HttpFailure.class).satisfies(e -> assertThat(((RecoveryBenchmarkClient.HttpFailure) e).apiCode).isEqualTo("NOT_FOUND"));
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
    @Test void createPostNeverRetriesOnTransientFailureWithoutCallerRedelivery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/create", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(503, 0); exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> client(server).post("/create", java.util.Map.of("question", "q"), "key"))
                    .isInstanceOf(RecoveryBenchmarkClient.HttpFailure.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
    @Test void standaloneReadRetriesOnlyThreeTimesOnTransientFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/state", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(503, 0); exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> client(server).get("/state")).isInstanceOf(RecoveryBenchmarkClient.HttpFailure.class);
            assertThat(calls.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }
    @Test void standaloneReadDoesNotRetryMissingAuthority() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/state", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(404, 0); exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> client(server).get("/state")).isInstanceOf(RecoveryBenchmarkClient.HttpFailure.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test void websocketTransientConnectionFailureIsRetriedAndRecorded() throws Exception {
        var http = mock(HttpClient.class);
        var builder = mock(WebSocket.Builder.class, RETURNS_SELF);
        var socket = mock(WebSocket.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        AtomicInteger connections = new AtomicInteger();
        when(builder.buildAsync(any(), any())).thenAnswer(call -> {
            if (connections.incrementAndGet() == 1) return CompletableFuture.failedFuture(new IOException());
            WebSocket.Listener listener = call.getArgument(1);
            when(socket.sendText(any(), eq(true))).thenAnswer(sent -> {
                listener.onText(socket, "{\"type\":\"SUBSCRIBED\",\"executionId\":\"exec\",\"knowledgeBaseId\":1,"
                        + "\"statusUrl\":\"/api/v1/agent/executions/exec?knowledgeBaseId=1\",\"latestSequence\":7}", true);
                return CompletableFuture.completedFuture(socket);
            });
            return CompletableFuture.completedFuture(socket);
        });
        try (var subscription = socketClient(http).subscribe("exec", 1)) {
            assertThat(subscription.connectionAttempts()).isEqualTo(2);
            assertThat(subscription.latestSequence()).isEqualTo(7);
        }
        verify(socket).abort();
    }

    @Test void websocketRetriesAreBoundedWithoutInventingSuccessfulInjection() {
        var http = mock(HttpClient.class);
        var builder = mock(WebSocket.Builder.class, RETURNS_SELF);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(new IOException("WS_CONNECTION_ERROR")));
        assertThatThrownBy(() -> socketClient(http).subscribe("exec", 1)).hasCauseInstanceOf(IOException.class);
        verify(builder, times(3)).buildAsync(any(), any());
    }

    @Test void websocketRejectedScopeIsNotRetried() {
        var http = mock(HttpClient.class);
        var builder = mock(WebSocket.Builder.class, RETURNS_SELF);
        var socket = mock(WebSocket.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenAnswer(call -> {
            WebSocket.Listener listener = call.getArgument(1);
            when(socket.sendText(any(), eq(true))).thenAnswer(sent -> {
                listener.onText(socket, "{\"type\":\"SUBSCRIPTION_REJECTED\",\"code\":\"FORBIDDEN\"}", true);
                return CompletableFuture.completedFuture(socket);
            });
            return CompletableFuture.completedFuture(socket);
        });
        assertThatThrownBy(() -> socketClient(http).subscribe("exec", 1)).hasRootCauseMessage("WS_SUBSCRIPTION_REJECTED");
        verify(builder).buildAsync(any(), any());
        verify(socket).abort();
    }

    @Test void websocketUnauthorizedHandshakeIsNotRetried() {
        var http = mock(HttpClient.class);
        var builder = mock(WebSocket.Builder.class, RETURNS_SELF);
        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(new WebSocketHandshakeException(response)));
        assertThatThrownBy(() -> socketClient(http).subscribe("exec", 1)).hasCauseInstanceOf(WebSocketHandshakeException.class);
        verify(builder).buildAsync(any(), any());
    }

    @Test void asyncErrorsKeepSafeCauseCodeWithoutLoggingRawMessages() {
        assertThat(RecoveryBenchmarkRunner.safeError(new java.util.concurrent.ExecutionException(new IOException("WS_CONNECTION_CLOSED"))))
                .isEqualTo("WS_CONNECTION_CLOSED");
        assertThat(RecoveryBenchmarkRunner.safeError(new java.util.concurrent.ExecutionException(new IOException("private response or credential"))))
                .isEqualTo("IOException");
    }

    private RecoveryBenchmarkClient socketClient(HttpClient http) {
        var environment = mock(RecoveryFaultEnvironment.class);
        when(environment.baseUrl()).thenReturn("http://127.0.0.1:12345");
        when(environment.token()).thenReturn("test-only-token");
        return new RecoveryBenchmarkClient(new JsonMapper(), environment, http);
    }
}
