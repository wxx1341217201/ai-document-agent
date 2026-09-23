package com.wxx.aidocumentagent.vector;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** M07 的 Qdrant 与 embedding 参数全部由部署配置提供，源码中不保存密钥。 */
@Validated
@ConfigurationProperties(prefix = "app.vector")
public class VectorProperties {

    private boolean enabled = true;

    @Valid
    private Qdrant qdrant = new Qdrant();

    @Valid
    private Embedding embedding = new Embedding();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Qdrant getQdrant() {
        return qdrant;
    }

    public void setQdrant(Qdrant qdrant) {
        this.qdrant = qdrant;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public static class Qdrant {

        @NotBlank
        private String host = "localhost";

        @Min(1)
        private int httpPort = 6333;

        @Min(1)
        private int grpcPort = 6334;

        @NotBlank
        private String collectionName = "ai_document_chunks";

        /** 远端 Qdrant 使用环境变量绑定；本地 Compose 可以为空。 */
        private String apiKey;

        private boolean useTls;

        @NotNull
        private VectorInitializationStrategy initializationStrategy = VectorInitializationStrategy.CREATE_IF_MISSING;

        @NotNull
        private VectorDistance distance = VectorDistance.COSINE;

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(3);

        @NotNull
        private Duration requestTimeout = Duration.ofSeconds(5);

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getHttpPort() {
            return httpPort;
        }

        public void setHttpPort(int httpPort) {
            this.httpPort = httpPort;
        }

        public int getGrpcPort() {
            return grpcPort;
        }

        public void setGrpcPort(int grpcPort) {
            this.grpcPort = grpcPort;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isUseTls() {
            return useTls;
        }

        public void setUseTls(boolean useTls) {
            this.useTls = useTls;
        }

        public VectorInitializationStrategy getInitializationStrategy() {
            return initializationStrategy;
        }

        public void setInitializationStrategy(VectorInitializationStrategy initializationStrategy) {
            this.initializationStrategy = initializationStrategy;
        }

        public VectorDistance getDistance() {
            return distance;
        }

        public void setDistance(VectorDistance distance) {
            this.distance = distance;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        @AssertTrue(message = "app.vector.qdrant.connect-timeout必须大于0")
        public boolean isConnectTimeoutPositive() {
            return connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative();
        }

        @AssertTrue(message = "app.vector.qdrant.request-timeout必须大于0")
        public boolean isRequestTimeoutPositive() {
            return requestTimeout != null && !requestTimeout.isZero() && !requestTimeout.isNegative();
        }
    }

    public static class Embedding {

        @NotBlank
        private String model = "text-embedding-3-small";

        @Min(1)
        private int dimensions = 1536;

        /** 单次交给 Spring AI VectorStore 的最大 chunk 数，避免逐 chunk 调模型。 */
        @Min(2)
        private int batchSize = 32;

        @NotNull
        private Duration timeout = Duration.ofSeconds(20);

        @Min(0)
        private int maxRetries = 2;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @AssertTrue(message = "app.vector.embedding.timeout必须大于0")
        public boolean isTimeoutPositive() {
            return timeout != null && !timeout.isZero() && !timeout.isNegative();
        }
    }
}
