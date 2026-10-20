package com.wxx.aidocumentagent.keyword.infrastructure;

import java.util.function.Function;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import com.wxx.aidocumentagent.keyword.KeywordIndexExceptionTranslator;
import com.wxx.aidocumentagent.keyword.KeywordProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentChunksIndexInitializerTest {

    @Test
    void mapping明确包含稳定标识BM25与中文ngram子字段() {
        String mapping = DocumentChunksIndexInitializer.mapping().toString();

        assertThat(mapping).contains("chunkId", "knowledgeBaseId", "documentId", "contentHash")
                .contains("content", "title", "sectionTitle")
                .contains("BM25", DocumentChunksIndexInitializer.NGRAM_SUB_FIELD)
                .contains("dynamic\":\"strict");
    }

    @Test
    void settings只使用官方内置ngram分析链且限制gram跨度() {
        String settings = DocumentChunksIndexInitializer.settings().toString();

        assertThat(settings).contains(DocumentChunksIndexInitializer.NGRAM_TOKENIZER,
                        DocumentChunksIndexInitializer.NGRAM_ANALYZER)
                .contains("ngram", "min_gram\":2", "max_gram\":3", "lowercase");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readOnlyValidationCannotCreateAMissingIndex() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(false));
        var initializer = new DocumentChunksIndexInitializer(client, new KeywordProperties(), new KeywordIndexExceptionTranslator());
        assertThatThrownBy(initializer::validateExisting).isInstanceOf(KeywordIndexException.class);
        verify(indices, never()).create(any(Function.class));
        verify(indices, never()).putAlias(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readOnlyValidationCannotRepairAMissingAlias() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(indices.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(false));
        var initializer = new DocumentChunksIndexInitializer(client, new KeywordProperties(), new KeywordIndexExceptionTranslator());
        assertThatThrownBy(initializer::validateExisting).isInstanceOf(KeywordIndexException.class);
        verify(indices, never()).create(any(Function.class));
        verify(indices, never()).putAlias(any(Function.class));
    }
}
