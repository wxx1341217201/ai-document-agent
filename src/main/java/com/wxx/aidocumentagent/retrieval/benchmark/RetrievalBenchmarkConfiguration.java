package com.wxx.aidocumentagent.retrieval.benchmark;

import java.net.URI;

import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorIndexExceptionTranslator;
import com.wxx.aidocumentagent.vector.VectorProperties;
import com.wxx.aidocumentagent.vector.infrastructure.ClassifyingEmbeddingModel;
import com.wxx.aidocumentagent.vector.infrastructure.QdrantCollectionInitializer;
import com.wxx.aidocumentagent.vector.infrastructure.QdrantVectorIndex;
import io.qdrant.client.QdrantClient;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.benchmark.retrieval", name = "enabled", havingValue = "true")
class RetrievalBenchmarkConfiguration {
    @Bean
    @Primary
    VectorIndex benchmarkVectorIndex(QdrantClient qdrant, VectorProperties properties,
                                     QdrantCollectionInitializer initializer, VectorIndexExceptionTranslator translator,
                                     Environment environment, ObjectMapper mapper) {
        initializer.initialize();
        var embedding = new BenchmarkHttpEmbeddingModel(
                URI.create(environment.getRequiredProperty("spring.ai.openai.embedding.base-url")),
                environment.getRequiredProperty("spring.ai.openai.embedding.api-key"),
                properties.getEmbedding().getModel(), properties.getEmbedding().getDimensions(),
                properties.getEmbedding().getTimeout(), mapper);
        var store = QdrantVectorStore.builder(qdrant, new ClassifyingEmbeddingModel(embedding, properties, translator))
                .collectionName(properties.getQdrant().getCollectionName()).initializeSchema(false).build();
        return new QdrantVectorIndex(store, properties, translator);
    }
}
