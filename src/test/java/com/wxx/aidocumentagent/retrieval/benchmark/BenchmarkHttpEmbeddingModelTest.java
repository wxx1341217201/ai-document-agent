package com.wxx.aidocumentagent.retrieval.benchmark;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import com.wxx.aidocumentagent.vector.VectorIndexErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingOptions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkHttpEmbeddingModelTest {
    @Test
    void mapsProviderIndicesBackToInputsAndIncludesDimensions() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var request = new JsonMapper().readTree(exchange.getRequestBody().readAllBytes());
            assertThat(request.get("dimensions").asInt()).isEqualTo(2);
            assertThat(request.get("input").size()).isEqualTo(2);
            assertThat(exchange.getProtocol()).isEqualTo("HTTP/1.1");
            byte[] body = "{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = model(server, Duration.ofSeconds(2));
            var output = model.call(new EmbeddingRequest(List.of("one", "two"), EmbeddingOptions.builder().build()));
            assertThat(output.getResults().getFirst().getOutput()).containsExactly(1, 0);
            assertThat(output.getResults().getLast().getOutput()).containsExactly(0, 1);
        }
        finally { server.stop(0); }
    }

    @Test
    void rejectsIncorrectDimensionsAndSanitizesProviderFailure() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[1]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> model(server, Duration.ofSeconds(2)).embed("query"))
                    .isInstanceOfSatisfying(VectorIndexException.class, failure ->
                            assertThat(failure.getErrorCode()).isEqualTo(VectorIndexErrorCode.VECTOR_DIMENSION_MISMATCH));
            server.removeContext("/embeddings");
            server.createContext("/embeddings", exchange -> {
                byte[] body = "secret provider body".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            assertThatThrownBy(() -> model(server, Duration.ofSeconds(2)).embed("query"))
                    .isInstanceOfSatisfying(VectorIndexException.class, failure -> {
                        assertThat(failure.getErrorCode()).isEqualTo(VectorIndexErrorCode.EMBEDDING_RATE_LIMITED);
                        assertThat(failure.getMessage()).doesNotContain("secret provider body");
                    });
        }
        finally { server.stop(0); }
    }

    private static BenchmarkHttpEmbeddingModel model(HttpServer server, Duration timeout) {
        return new BenchmarkHttpEmbeddingModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "test", "test", 2, timeout, new JsonMapper());
    }
}
