package com.wxx.aidocumentagent.keyword.infrastructure;

import java.io.IOException;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.analysis.TokenChar;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import com.wxx.aidocumentagent.keyword.KeywordIndexExceptionTranslator;
import com.wxx.aidocumentagent.keyword.KeywordProperties;

/**
 * 以版本化索引名创建 M08 mapping。ES 默认 BM25 之外显式标注 text 字段相似度为 BM25。
 *
 * <p>中文不依赖 IK 等镜像外插件：standard 字段保留英文/编号精确词，ngram 子字段以官方内置
 * n-gram tokenizer 生成 2~3 字符片段。它能覆盖常见中文关键词，但不提供词典级中文分词。</p>
 */
public final class DocumentChunksIndexInitializer {

    static final String NGRAM_TOKENIZER = "zh_keyword_ngram_tokenizer";
    static final String NGRAM_ANALYZER = "zh_keyword_ngram";
    static final String NGRAM_SUB_FIELD = "ngram";

    private final ElasticsearchClient client;
    private final KeywordProperties properties;
    private final KeywordIndexExceptionTranslator exceptionTranslator;

    public DocumentChunksIndexInitializer(ElasticsearchClient client, KeywordProperties properties,
                                          KeywordIndexExceptionTranslator exceptionTranslator) {
        this.client = client;
        this.properties = properties;
        this.exceptionTranslator = exceptionTranslator;
    }

    /** 创建 document_chunks_v1；已有索引只允许复用指向该版本的单一别名。 */
    public void initialize() {
        try {
            if (!client.indices().exists(request -> request.index(properties.getIndexName())).value()) {
                client.indices().create(request -> request
                        .index(properties.getIndexName())
                        .settings(settings())
                        .mappings(mapping())
                        .aliases(properties.getAlias(), alias -> alias.isWriteIndex(true)));
            }
            ensureAlias();
        }
        catch (IOException | RuntimeException exception) {
            if (exception instanceof KeywordIndexException keywordIndexException) {
                throw keywordIndexException;
            }
            throw exceptionTranslator.translate(exception);
        }
    }

    private void ensureAlias() throws IOException {
        if (!client.indices().existsAlias(request -> request.name(properties.getAlias())).value()) {
            client.indices().putAlias(request -> request.index(properties.getIndexName()).name(properties.getAlias())
                    .isWriteIndex(true));
            return;
        }
        var aliases = client.indices().getAlias(request -> request.name(properties.getAlias())).aliases();
        if (aliases.size() != 1 || !aliases.containsKey(properties.getIndexName())) {
            throw new KeywordIndexException(KeywordIndexErrorCode.ELASTICSEARCH_INDEX_CONFIGURATION_MISMATCH,
                    "Elasticsearch关键词别名未指向预期版本索引");
        }
    }

    static IndexSettings settings() {
        return IndexSettings.of(settings -> settings
                .numberOfShards("1")
                .numberOfReplicas("0")
                .maxNgramDiff(1)
                .analysis(analysis -> analysis
                        .tokenizer(NGRAM_TOKENIZER, tokenizer -> tokenizer.definition(definition -> definition
                                .ngram(ngram -> ngram.minGram(2).maxGram(3)
                                        .tokenChars(TokenChar.Letter, TokenChar.Digit))))
                        .analyzer(NGRAM_ANALYZER, analyzer -> analyzer.custom(custom -> custom
                                .tokenizer(NGRAM_TOKENIZER)
                                .filter("lowercase")))));
    }

    static TypeMapping mapping() {
        return TypeMapping.of(mapping -> mapping
                .dynamic(DynamicMapping.Strict)
                .properties(KeywordDocument.CHUNK_ID, property -> property.long_(number -> number))
                .properties(KeywordDocument.KNOWLEDGE_BASE_ID, property -> property.long_(number -> number))
                .properties(KeywordDocument.DOCUMENT_ID, property -> property.long_(number -> number))
                .properties(KeywordDocument.CONTENT, property -> property.text(text -> text
                        .analyzer("standard")
                        .searchAnalyzer("standard")
                        .similarity("BM25")
                        .fields(NGRAM_SUB_FIELD, ngram -> ngram.text(subField -> subField
                                .analyzer(NGRAM_ANALYZER)
                                .searchAnalyzer(NGRAM_ANALYZER)
                                .similarity("BM25")))))
                .properties(KeywordDocument.TITLE, property -> property.text(text -> text
                        .analyzer("standard")
                        .searchAnalyzer("standard")
                        .similarity("BM25")
                        .fields(NGRAM_SUB_FIELD, ngram -> ngram.text(subField -> subField
                                .analyzer(NGRAM_ANALYZER)
                                .searchAnalyzer(NGRAM_ANALYZER)
                                .similarity("BM25")))))
                .properties(KeywordDocument.SECTION_TITLE, property -> property.text(text -> text
                        .analyzer("standard")
                        .searchAnalyzer("standard")
                        .similarity("BM25")
                        .fields(NGRAM_SUB_FIELD, ngram -> ngram.text(subField -> subField
                                .analyzer(NGRAM_ANALYZER)
                                .searchAnalyzer(NGRAM_ANALYZER)
                                .similarity("BM25")))))
                .properties(KeywordDocument.PAGE_FROM, property -> property.integer(number -> number))
                .properties(KeywordDocument.PAGE_TO, property -> property.integer(number -> number))
                .properties(KeywordDocument.CONTENT_HASH, property -> property.keyword(keyword -> keyword)));
    }
}
