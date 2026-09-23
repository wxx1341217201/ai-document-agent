package com.wxx.aidocumentagent.vector.infrastructure;

import java.time.Duration;

import com.google.common.util.concurrent.Futures;
import com.wxx.aidocumentagent.vector.VectorDistance;
import com.wxx.aidocumentagent.vector.VectorIndexErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import com.wxx.aidocumentagent.vector.VectorIndexExceptionTranslator;
import com.wxx.aidocumentagent.vector.VectorInitializationStrategy;
import com.wxx.aidocumentagent.vector.VectorProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantCollectionInitializerTest {

    @Test
    void 缺失collection按配置创建并校验Cosine维度() {
        QdrantClient client = mock(QdrantClient.class);
        VectorProperties properties = properties();
        stubHealth(client);
        when(client.collectionExistsAsync(eq("test_chunks"), any(Duration.class))).thenReturn(Futures.immediateFuture(false));
        when(client.createCollectionAsync(eq("test_chunks"), any(VectorParams.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(client.getCollectionInfoAsync(eq("test_chunks"), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(collection(4, Distance.Cosine)));

        new QdrantCollectionInitializer(client, properties, new VectorIndexExceptionTranslator()).initialize();

        ArgumentCaptor<VectorParams> params = ArgumentCaptor.forClass(VectorParams.class);
        verify(client).createCollectionAsync(eq("test_chunks"), params.capture(), eq(Duration.ofSeconds(2)));
        assertThat(params.getValue().getSize()).isEqualTo(4);
        assertThat(params.getValue().getDistance()).isEqualTo(Distance.Cosine);
    }

    @Test
    void 已有collection维度或距离不匹配时明确失败() {
        QdrantClient client = mock(QdrantClient.class);
        VectorProperties properties = properties();
        stubHealth(client);
        when(client.collectionExistsAsync(eq("test_chunks"), any(Duration.class))).thenReturn(Futures.immediateFuture(true));
        when(client.getCollectionInfoAsync(eq("test_chunks"), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(collection(8, Distance.Dot)));

        assertThatThrownBy(() -> new QdrantCollectionInitializer(client, properties,
                new VectorIndexExceptionTranslator()).initialize())
                .isInstanceOfSatisfying(VectorIndexException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(VectorIndexErrorCode.VECTOR_COLLECTION_CONFIGURATION_MISMATCH))
                .hasMessageContaining("expected dimensions=4");
    }

    @Test
    void validateExisting策略不会静默创建缺失collection() {
        QdrantClient client = mock(QdrantClient.class);
        VectorProperties properties = properties();
        properties.getQdrant().setInitializationStrategy(VectorInitializationStrategy.VALIDATE_EXISTING);
        stubHealth(client);
        when(client.collectionExistsAsync(eq("test_chunks"), any(Duration.class))).thenReturn(Futures.immediateFuture(false));

        assertThatThrownBy(() -> new QdrantCollectionInitializer(client, properties,
                new VectorIndexExceptionTranslator()).initialize())
                .isInstanceOfSatisfying(VectorIndexException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(VectorIndexErrorCode.VECTOR_COLLECTION_NOT_FOUND));
    }

    private void stubHealth(QdrantClient client) {
        when(client.healthCheckAsync(any(Duration.class))).thenReturn(Futures.immediateFuture(HealthCheckReply.getDefaultInstance()));
    }

    private VectorProperties properties() {
        VectorProperties properties = new VectorProperties();
        properties.getQdrant().setCollectionName("test_chunks");
        properties.getQdrant().setConnectTimeout(Duration.ofSeconds(1));
        properties.getQdrant().setRequestTimeout(Duration.ofSeconds(2));
        properties.getQdrant().setDistance(VectorDistance.COSINE);
        properties.getEmbedding().setDimensions(4);
        return properties;
    }

    private CollectionInfo collection(long dimensions, Distance distance) {
        VectorParams params = VectorParams.newBuilder().setSize(dimensions).setDistance(distance).build();
        VectorsConfig vectors = VectorsConfig.newBuilder().setParams(params).build();
        CollectionParams collectionParams = CollectionParams.newBuilder().setVectorsConfig(vectors).build();
        CollectionConfig config = CollectionConfig.newBuilder().setParams(collectionParams).build();
        return CollectionInfo.newBuilder().setConfig(config).build();
    }
}
