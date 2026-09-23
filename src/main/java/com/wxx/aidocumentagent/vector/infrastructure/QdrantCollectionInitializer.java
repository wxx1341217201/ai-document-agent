package com.wxx.aidocumentagent.vector.infrastructure;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.util.concurrent.ListenableFuture;
import com.wxx.aidocumentagent.vector.VectorIndexErrorCode;
import com.wxx.aidocumentagent.vector.VectorIndexException;
import com.wxx.aidocumentagent.vector.VectorIndexExceptionTranslator;
import com.wxx.aidocumentagent.vector.VectorInitializationStrategy;
import com.wxx.aidocumentagent.vector.VectorProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.VectorParams;

/** 在 VectorStore 初始化前显式创建并校验 collection，禁止维度或距离类型静默混用。 */
public final class QdrantCollectionInitializer {

    private final QdrantClient qdrantClient;
    private final VectorProperties properties;
    private final VectorIndexExceptionTranslator exceptionTranslator;

    public QdrantCollectionInitializer(QdrantClient qdrantClient, VectorProperties properties,
                                       VectorIndexExceptionTranslator exceptionTranslator) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
        this.exceptionTranslator = exceptionTranslator;
    }

    public void initialize() {
        VectorProperties.Qdrant qdrant = properties.getQdrant();
        await(qdrantClient.healthCheckAsync(qdrant.getConnectTimeout()), qdrant.getConnectTimeout());
        boolean exists = await(qdrantClient.collectionExistsAsync(qdrant.getCollectionName(), qdrant.getRequestTimeout()),
                qdrant.getRequestTimeout());
        if (!exists) {
            if (qdrant.getInitializationStrategy() == VectorInitializationStrategy.VALIDATE_EXISTING) {
                throw new VectorIndexException(VectorIndexErrorCode.VECTOR_COLLECTION_NOT_FOUND,
                        "Qdrant collection '" + qdrant.getCollectionName() + "'不存在，初始化策略禁止创建");
            }
            VectorParams vectorParams = VectorParams.newBuilder()
                    .setSize(properties.getEmbedding().getDimensions())
                    .setDistance(qdrant.getDistance().qdrantDistance())
                    .build();
            await(qdrantClient.createCollectionAsync(qdrant.getCollectionName(), vectorParams,
                    qdrant.getRequestTimeout()), qdrant.getRequestTimeout());
        }
        CollectionInfo collection = await(qdrantClient.getCollectionInfoAsync(qdrant.getCollectionName(),
                qdrant.getRequestTimeout()), qdrant.getRequestTimeout());
        verifyCollectionShape(collection);
    }

    private void verifyCollectionShape(CollectionInfo collection) {
        VectorProperties.Qdrant qdrant = properties.getQdrant();
        if (!collection.hasConfig() || !collection.getConfig().hasParams()
                || !collection.getConfig().getParams().hasVectorsConfig()
                || !collection.getConfig().getParams().getVectorsConfig().hasParams()) {
            throw new VectorIndexException(VectorIndexErrorCode.VECTOR_COLLECTION_CONFIGURATION_MISMATCH,
                    "Qdrant collection '" + qdrant.getCollectionName() + "'不是单向量collection，无法安全复用");
        }
        VectorParams actual = collection.getConfig().getParams().getVectorsConfig().getParams();
        int expectedDimensions = properties.getEmbedding().getDimensions();
        if (actual.getSize() != expectedDimensions || actual.getDistance() != qdrant.getDistance().qdrantDistance()) {
            throw new VectorIndexException(VectorIndexErrorCode.VECTOR_COLLECTION_CONFIGURATION_MISMATCH,
                    "Qdrant collection '" + qdrant.getCollectionName() + "'向量配置不匹配: expected dimensions="
                            + expectedDimensions + ", distance=" + qdrant.getDistance() + "; actual dimensions="
                            + actual.getSize() + ", distance=" + actual.getDistance());
        }
    }

    private <T> T await(ListenableFuture<T> future, Duration timeout) {
        try {
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exceptionTranslator.translateVectorStore(exception);
        }
        catch (ExecutionException | TimeoutException exception) {
            throw exceptionTranslator.translateVectorStore(exception);
        }
    }
}
