package com.wxx.aidocumentagent.vector;

import com.wxx.aidocumentagent.vector.infrastructure.ClassifyingEmbeddingModel;
import com.wxx.aidocumentagent.vector.infrastructure.QdrantCollectionInitializer;
import com.wxx.aidocumentagent.vector.infrastructure.QdrantVectorIndex;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** 手动装配 Spring AI QdrantVectorStore，以便在任何读写前完成 collection 兼容性校验。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.vector", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(VectorProperties.class)
public class VectorConfiguration {

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(VectorProperties properties) {
        VectorProperties.Qdrant qdrant = properties.getQdrant();
        QdrantGrpcClient.Builder builder = QdrantGrpcClient
                .newBuilder(qdrant.getHost(), qdrant.getGrpcPort(), qdrant.isUseTls())
                .withTimeout(qdrant.getRequestTimeout());
        if (StringUtils.hasText(qdrant.getApiKey())) {
            builder.withApiKey(qdrant.getApiKey());
        }
        return new QdrantClient(builder.build());
    }

    @Bean
    QdrantCollectionInitializer qdrantCollectionInitializer(QdrantClient qdrantClient, VectorProperties properties,
                                                             VectorIndexExceptionTranslator exceptionTranslator) {
        return new QdrantCollectionInitializer(qdrantClient, properties, exceptionTranslator);
    }

    @Bean
    QdrantVectorStore qdrantVectorStore(QdrantClient qdrantClient, OpenAiEmbeddingModel openAiEmbeddingModel,
                                        VectorProperties properties, QdrantCollectionInitializer initializer,
                                        VectorIndexExceptionTranslator exceptionTranslator) {
        initializer.initialize();
        return QdrantVectorStore.builder(qdrantClient,
                        new ClassifyingEmbeddingModel(openAiEmbeddingModel, properties, exceptionTranslator))
                .collectionName(properties.getQdrant().getCollectionName())
                .initializeSchema(false)
                .build();
    }

    @Bean
    VectorIndex vectorIndex(QdrantVectorStore qdrantVectorStore, VectorProperties properties,
                            VectorIndexExceptionTranslator exceptionTranslator) {
        return new QdrantVectorIndex(qdrantVectorStore, properties, exceptionTranslator);
    }
}
