package com.wxx.aidocumentagent.retrieval.benchmark;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.keyword.infrastructure.DocumentChunksIndexInitializer;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RetrievalBenchmarkCorpusBootstrapperTest {

    @Test
    void refusesToWriteWhenAnyIndexNameIsNotIsolated() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorIndex vector = mock(VectorIndex.class);
        KeywordIndex keyword = mock(KeywordIndex.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.vector.qdrant.collection-name", "production_chunks")
                .withProperty("app.keyword.index-name", "document_chunks_benchmark_v1")
                .withProperty("app.keyword.alias", "document_chunks_benchmark");
        RetrievalBenchmarkCorpusBootstrapper bootstrapper = new RetrievalBenchmarkCorpusBootstrapper(jdbc, vector,
                keyword, new VectorProperties(), mock(DocumentChunksIndexInitializer.class),
                mock(ElasticsearchClient.class), environment);

        assertThatThrownBy(() -> bootstrapper.bootstrap(2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("isolated benchmark index");
        verifyNoInteractions(jdbc, vector, keyword);
    }
}
