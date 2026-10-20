package com.wxx.aidocumentagent.vector;

import java.util.ArrayList;
import java.util.List;

import com.google.common.util.concurrent.Futures;
import com.wxx.aidocumentagent.vector.infrastructure.QdrantCollectionInitializer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorConfigurationTest {
    private final QdrantClient client = mock(QdrantClient.class);
    private final OpenAiEmbeddingModel embedding = mock(OpenAiEmbeddingModel.class);
    private final QdrantCollectionInitializer initializer = mock(QdrantCollectionInitializer.class);
    private final VectorProperties properties = new VectorProperties();

    @Test
    void 校验集合后写入时接入自定义批处理和官方观测() {
        var beans = new DefaultListableBeanFactory();
        var names = new ArrayList<String>();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStop(Observation.Context context) { names.add(context.getName()); }
        });
        beans.registerSingleton("observations", registry);
        beans.registerSingleton("convention", new DefaultVectorStoreObservationConvention() {
            @Override public String getName() { return "test.vector.custom"; }
        });
        var batching = mock(BatchingStrategy.class);
        beans.registerSingleton("batching", batching);

        store(beans).add(List.of(new Document("content")));

        assertThat(names).containsExactly("test.vector.custom");
        var order = inOrder(initializer, embedding, client);
        order.verify(initializer).initialize();
        order.verify(embedding).embed(anyList(), any(EmbeddingOptions.class), same(batching));
        order.verify(client).upsertAsync(eq(properties.getQdrant().getCollectionName()), anyList());
    }

    @Test
    void 无可选观测配置时仍可使用官方默认批处理() {
        store(new DefaultListableBeanFactory()).add(List.of(new Document("content")));

        verify(embedding).embed(anyList(), any(EmbeddingOptions.class), isA(TokenCountBatchingStrategy.class));
    }

    @Test
    void 集合预检失败时不允许装配可写的向量存储() {
        doThrow(new IllegalStateException("incompatible collection")).when(initializer).initialize();

        assertThatThrownBy(() -> store(new DefaultListableBeanFactory()))
                .isInstanceOf(IllegalStateException.class).hasMessage("incompatible collection");
        verifyNoInteractions(client, embedding);
    }

    private org.springframework.ai.vectorstore.qdrant.QdrantVectorStore store(DefaultListableBeanFactory beans) {
        when(embedding.embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                .thenReturn(List.of(new float[properties.getEmbedding().getDimensions()]));
        when(client.upsertAsync(anyString(), anyList())).thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        return new VectorConfiguration().qdrantVectorStore(client, embedding, properties, initializer,
                new VectorIndexExceptionTranslator(), beans.getBeanProvider(ObservationRegistry.class),
                beans.getBeanProvider(VectorStoreObservationConvention.class), beans.getBeanProvider(BatchingStrategy.class));
    }
}
