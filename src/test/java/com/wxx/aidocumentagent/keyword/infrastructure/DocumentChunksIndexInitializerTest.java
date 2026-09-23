package com.wxx.aidocumentagent.keyword.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
