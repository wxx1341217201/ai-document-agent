package com.wxx.aidocumentagent.retrieval.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.wxx.aidocumentagent.vector.VectorIndexErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.ObjectMapper;

/** Explicit HTTP/1.1 benchmark transport for the same real OpenAI-compatible embedding endpoint. No cache/retry. */
final class BenchmarkHttpEmbeddingModel implements EmbeddingModel {
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final int dimensions;
    private final Duration timeout;
    private final ObjectMapper mapper;

    BenchmarkHttpEmbeddingModel(URI baseUrl, String key, String model, int dimensions, Duration timeout, ObjectMapper mapper) {
        this.endpoint = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/embeddings");
        this.key = key;
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout).build();
    }

    @Override public int dimensions() { return dimensions; }
    @Override public float[] embed(Document document) { return embed(document.getText()); }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        byte[] body = mapper.writeValueAsBytes(Map.of("model", model, "dimensions", dimensions,
                "input", request.getInstructions(), "encoding_format", "float"));
        HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            var response = client.send(http, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                throw new VectorIndexException(status == 429 ? VectorIndexErrorCode.EMBEDDING_RATE_LIMITED
                        : status == 401 || status == 403 ? VectorIndexErrorCode.EMBEDDING_AUTHENTICATION_FAILED
                        : VectorIndexErrorCode.EMBEDDING_REQUEST_FAILED);
            }
            var data = mapper.readTree(response.body()).get("data");
            if (data == null || !data.isArray() || data.size() != request.getInstructions().size()) {
                throw new VectorIndexException(VectorIndexErrorCode.VECTOR_DIMENSION_MISMATCH);
            }
            var indexed = new Embedding[data.size()];
            for (var item : data) {
                int index = item.path("index").asInt(-1);
                var values = item.get("embedding");
                if (index < 0 || index >= indexed.length || indexed[index] != null || values == null
                        || !values.isArray() || values.size() != dimensions) {
                    throw new VectorIndexException(VectorIndexErrorCode.VECTOR_DIMENSION_MISMATCH);
                }
                float[] vector = new float[dimensions];
                for (int column = 0; column < dimensions; column++) {
                    if (!values.get(column).isNumber()) { throw new VectorIndexException(VectorIndexErrorCode.EMBEDDING_REQUEST_FAILED); }
                    vector[column] = (float) values.get(column).asDouble();
                    if (!Float.isFinite(vector[column])) { throw new VectorIndexException(VectorIndexErrorCode.EMBEDDING_REQUEST_FAILED); }
                }
                indexed[index] = new Embedding(vector, index);
            }
            return new EmbeddingResponse(java.util.List.of(indexed));
        }
        catch (java.net.http.HttpTimeoutException failure) {
            throw new VectorIndexException(VectorIndexErrorCode.EMBEDDING_TIMEOUT);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new VectorIndexException(VectorIndexErrorCode.EMBEDDING_TIMEOUT);
        }
        catch (IOException failure) {
            throw new VectorIndexException(VectorIndexErrorCode.EMBEDDING_REQUEST_FAILED);
        }
    }
}
