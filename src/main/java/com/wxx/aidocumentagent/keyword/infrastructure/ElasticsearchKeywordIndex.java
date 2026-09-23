package com.wxx.aidocumentagent.keyword.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordHit;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import com.wxx.aidocumentagent.keyword.KeywordIndexExceptionTranslator;
import com.wxx.aidocumentagent.keyword.KeywordProperties;
import com.wxx.aidocumentagent.keyword.KeywordQuery;

/** Elasticsearch Java Client 适配器：所有写入经 alias，所有读取/删除都以 knowledgeBaseId 过滤。 */
public final class ElasticsearchKeywordIndex implements KeywordIndex {

    private static final List<String> STANDARD_FIELDS = List.of(
            KeywordDocument.TITLE + "^4",
            KeywordDocument.SECTION_TITLE + "^3",
            KeywordDocument.CONTENT + "^2");
    private static final List<String> NGRAM_FIELDS = List.of(
            KeywordDocument.TITLE + "." + DocumentChunksIndexInitializer.NGRAM_SUB_FIELD + "^4",
            KeywordDocument.SECTION_TITLE + "." + DocumentChunksIndexInitializer.NGRAM_SUB_FIELD + "^3",
            KeywordDocument.CONTENT + "." + DocumentChunksIndexInitializer.NGRAM_SUB_FIELD + "^2");

    private final ElasticsearchClient client;
    private final KeywordProperties properties;
    private final KeywordIndexExceptionTranslator exceptionTranslator;

    public ElasticsearchKeywordIndex(ElasticsearchClient client, KeywordProperties properties,
                                     KeywordIndexExceptionTranslator exceptionTranslator) {
        this.client = client;
        this.properties = properties;
        this.exceptionTranslator = exceptionTranslator;
    }

    @Override
    public void upsert(List<KeywordDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<KeywordDocument> documents = List.copyOf(chunks);
        for (int start = 0; start < documents.size(); start += properties.getBulkSize()) {
            int end = Math.min(documents.size(), start + properties.getBulkSize());
            upsertBatch(documents.subList(start, end));
        }
    }

    private void upsertBatch(List<KeywordDocument> documents) {
        BulkRequest request = BulkRequest.of(builder -> builder
                .index(properties.getAlias())
                .requireAlias(true)
                .operations(documents.stream().map(this::indexOperation).toList()));
        try {
            var response = client.bulk(request);
            // 不只依赖 response.errors()；逐项检查每个 bulk item，保证部分失败不会被误报成功。
            for (var item : response.items()) {
                if (item.error() != null || item.status() < 200 || item.status() >= 300) {
                    throw exceptionTranslator.bulkItemFailure(item.status());
                }
            }
            if (response.errors()) {
                throw new KeywordIndexException(com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode
                        .ELASTICSEARCH_BULK_ITEM_FAILED);
            }
        }
        catch (IOException | RuntimeException exception) {
            throw exceptionTranslator.translate(exception);
        }
    }

    private BulkOperation indexOperation(KeywordDocument document) {
        return BulkOperation.of(operation -> operation.index(index -> index
                .id(document.elasticsearchId())
                .requireAlias(true)
                .document(document)));
    }

    @Override
    public List<KeywordHit> search(KeywordQuery query) {
        try {
            var request = co.elastic.clients.elasticsearch.core.SearchRequest.of(builder -> builder
                    .index(properties.getAlias())
                    .size(query.topK())
                    .trackTotalHits(trackHits -> trackHits.enabled(false))
                    .query(keywordQuery(query)));
            var response = client.search(request, KeywordDocument.class);
            List<KeywordHit> hits = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                KeywordDocument source = hit.source();
                // ES filter 是第一道边界；映射结果时再验证一次，防御旧索引或错误响应串库。
                if (source != null && source.knowledgeBaseId() == query.knowledgeBaseId()) {
                    hits.add(new KeywordHit(source.chunkId(), source.knowledgeBaseId(), source.documentId(),
                            source.content(), source.title(), source.sectionTitle(), source.pageFrom(), source.pageTo(),
                            source.contentHash(), hit.score() == null ? 0D : hit.score()));
                }
            }
            return List.copyOf(hits);
        }
        catch (IOException | RuntimeException exception) {
            throw exceptionTranslator.translate(exception);
        }
    }

    @Override
    public void deleteByDocument(long knowledgeBaseId, long documentId) {
        if (knowledgeBaseId <= 0 || documentId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId和documentId必须大于0");
        }
        try {
            var request = co.elastic.clients.elasticsearch.core.DeleteByQueryRequest.of(builder -> builder
                    .index(properties.getAlias())
                    .refresh(true)
                    .query(scopedDocumentQuery(knowledgeBaseId, documentId)));
            var response = client.deleteByQuery(request);
            // delete-by-query 可以在 HTTP 200 中携带分片失败或超时；此处必须让上层进入既有的有限重试/失败流程。
            if (Boolean.TRUE.equals(response.timedOut())) {
                throw new KeywordIndexException(com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode
                        .ELASTICSEARCH_TIMEOUT);
            }
            if (response.failures() != null && !response.failures().isEmpty()) {
                throw exceptionTranslator.bulkItemFailure(response.failures().getFirst().status());
            }
            if (response.versionConflicts() != null && response.versionConflicts() > 0) {
                throw new KeywordIndexException(com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode
                        .ELASTICSEARCH_BULK_ITEM_FAILED, "Elasticsearch删除索引项发生版本冲突");
            }
        }
        catch (IOException | RuntimeException exception) {
            throw exceptionTranslator.translate(exception);
        }
    }

    private Query keywordQuery(KeywordQuery query) {
        return Query.of(root -> root.bool(bool -> bool
                .filter(filter -> filter.term(term -> term.field(KeywordDocument.KNOWLEDGE_BASE_ID)
                        .value(query.knowledgeBaseId())))
                .should(should -> should.multiMatch(multiMatch -> multiMatch
                        .query(query.text())
                        .fields(STANDARD_FIELDS)
                        .type(TextQueryType.BestFields)))
                .should(should -> should.multiMatch(multiMatch -> multiMatch
                        .query(query.text())
                        .fields(NGRAM_FIELDS)
                        .operator(Operator.And)
                        .type(TextQueryType.BestFields)))
                .should(should -> should.matchPhrase(phrase -> phrase
                        .field(KeywordDocument.CONTENT)
                        .query(query.text())))
                .minimumShouldMatch("1")));
    }

    private Query scopedDocumentQuery(long knowledgeBaseId, long documentId) {
        return Query.of(root -> root.bool(bool -> bool
                .filter(filter -> filter.term(term -> term.field(KeywordDocument.KNOWLEDGE_BASE_ID)
                        .value(knowledgeBaseId)))
                .filter(filter -> filter.term(term -> term.field(KeywordDocument.DOCUMENT_ID)
                        .value(documentId)))));
    }
}
