package com.wxx.aidocumentagent.keyword.infrastructure;

import java.util.List;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.wxx.aidocumentagent.keyword.KeywordDocument;
import com.wxx.aidocumentagent.keyword.KeywordHit;
import com.wxx.aidocumentagent.keyword.KeywordIndexErrorCode;
import com.wxx.aidocumentagent.keyword.KeywordIndexException;
import com.wxx.aidocumentagent.keyword.KeywordIndexExceptionTranslator;
import com.wxx.aidocumentagent.keyword.KeywordProperties;
import com.wxx.aidocumentagent.keyword.KeywordQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ElasticsearchKeywordIndexTest {

    @Mock private ElasticsearchClient client;

    private ElasticsearchKeywordIndex index;

    @BeforeEach
    void setUp() {
        KeywordProperties properties = new KeywordProperties();
        properties.setBulkSize(2);
        index = new ElasticsearchKeywordIndex(client, properties, new KeywordIndexExceptionTranslator());
    }

    @Test
    void 重复写入以稳定chunkId覆盖并逐项检查bulk响应() throws Exception {
        BulkResponse first = successfulBulkResponse(2);
        BulkResponse second = successfulBulkResponse(1);
        BulkResponse third = successfulBulkResponse(2);
        BulkResponse fourth = successfulBulkResponse(1);
        when(client.bulk(any(BulkRequest.class))).thenReturn(first, second, third, fourth);
        List<KeywordDocument> documents = List.of(
                document(1001L, 9L, 101L, "向量检索错误码 ERR-1042", "操作手册"),
                document(1002L, 9L, 101L, "第二段", "操作手册"),
                document(1003L, 9L, 101L, "第三段", "操作手册"));

        index.upsert(documents);
        index.upsert(documents);

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, times(4)).bulk(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.index()).isEqualTo("document_chunks");
            assertThat(request.requireAlias()).isTrue();
            assertThat(request.operations()).allSatisfy(operation -> {
                assertThat(operation.isIndex()).isTrue();
                assertThat(operation.index().requireAlias()).isTrue();
                assertThat(operation.index().id()).isEqualTo(((KeywordDocument) operation.index().document())
                        .elasticsearchId());
            });
        });
    }

    @Test
    void bulk单项临时失败不会被误报成功且可进入有限重试() throws Exception {
        BulkResponseItem item = org.mockito.Mockito.mock(BulkResponseItem.class);
        when(item.status()).thenReturn(429);
        when(item.error()).thenReturn(org.mockito.Mockito.mock(co.elastic.clients.elasticsearch._types.ErrorCause.class));
        BulkResponse response = org.mockito.Mockito.mock(BulkResponse.class);
        when(response.items()).thenReturn(List.of(item));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> index.upsert(List.of(document(1001L, 9L, 101L, "ERR-1042", "手册"))))
                .isInstanceOfSatisfying(KeywordIndexException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(KeywordIndexErrorCode.ELASTICSEARCH_REJECTED);
                    assertThat(exception.isRetryable()).isTrue();
                });
    }

    @Test
    void 编号与中文关键词查询始终带知识库filter且二次过滤跨库命中() throws Exception {
        KeywordDocument own = document(1001L, 9L, 101L, "Qdrant 向量检索返回错误码 ERR-1042", "检索故障排查");
        KeywordDocument foreign = document(2001L, 10L, 202L, "Qdrant 向量检索返回错误码 ERR-1042", "其他知识库");
        SearchResponse<KeywordDocument> response = searchResponse(own, foreign);
        when(client.search(any(SearchRequest.class), eq(KeywordDocument.class)))
                .thenReturn(response);

        List<KeywordHit> numberHits = index.search(new KeywordQuery(9L, "ERR-1042", 5));
        List<KeywordHit> chineseHits = index.search(new KeywordQuery(9L, "向量检索", 5));
        List<KeywordHit> properNounHits = index.search(new KeywordQuery(9L, "Qdrant", 5));

        assertThat(numberHits).singleElement().satisfies(hit -> {
            assertThat(hit.chunkId()).isEqualTo(1001L);
            assertThat(hit.content()).contains("ERR-1042");
        });
        assertThat(chineseHits).singleElement().extracting(KeywordHit::knowledgeBaseId).isEqualTo(9L);
        assertThat(properNounHits).singleElement().extracting(KeywordHit::chunkId).isEqualTo(1001L);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client, times(3)).search(captor.capture(), eq(KeywordDocument.class));
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.index()).containsExactly("document_chunks");
            assertThat(request.query().toString()).contains("knowledgeBaseId", "9");
        });
        assertThat(captor.getAllValues().getFirst().query().toString()).contains("ERR-1042");
        assertThat(captor.getAllValues().get(1).query().toString()).contains("向量检索");
        assertThat(captor.getAllValues().get(2).query().toString()).contains("Qdrant");
    }

    @Test
    void 按文档删除同时带知识库和文档边界() throws Exception {
        DeleteByQueryResponse response = org.mockito.Mockito.mock(DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(false);
        when(response.failures()).thenReturn(List.of());
        when(response.versionConflicts()).thenReturn(0L);
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(response);

        index.deleteByDocument(9L, 101L);

        ArgumentCaptor<DeleteByQueryRequest> captor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(client).deleteByQuery(captor.capture());
        assertThat(captor.getValue().index()).containsExactly("document_chunks");
        assertThat(captor.getValue().refresh()).isTrue();
        assertThat(captor.getValue().query().toString()).contains("knowledgeBaseId", "documentId", "9", "101");
    }

    @Test
    void 删除响应超时不会被误报为旧索引已清理() throws Exception {
        DeleteByQueryResponse response = org.mockito.Mockito.mock(DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(true);
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> index.deleteByDocument(9L, 101L))
                .isInstanceOfSatisfying(KeywordIndexException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(KeywordIndexErrorCode.ELASTICSEARCH_TIMEOUT);
                    assertThat(exception.isRetryable()).isTrue();
                });
    }

    private KeywordDocument document(long chunkId, long knowledgeBaseId, long documentId, String content, String title) {
        return new KeywordDocument(chunkId, knowledgeBaseId, documentId, content, title, "排障", 3, 3,
                "a".repeat(64));
    }

    private BulkResponse successfulBulkResponse(int itemCount) {
        List<BulkResponseItem> items = java.util.stream.IntStream.range(0, itemCount)
                .mapToObj(ignored -> {
                    BulkResponseItem item = org.mockito.Mockito.mock(BulkResponseItem.class);
                    when(item.status()).thenReturn(201);
                    return item;
                }).toList();
        BulkResponse response = org.mockito.Mockito.mock(BulkResponse.class);
        when(response.items()).thenReturn(items);
        when(response.errors()).thenReturn(false);
        return response;
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<KeywordDocument> searchResponse(KeywordDocument... documents) {
        List<Hit<KeywordDocument>> hits = java.util.Arrays.stream(documents).map(document -> {
            Hit<KeywordDocument> hit = org.mockito.Mockito.mock(Hit.class);
            when(hit.source()).thenReturn(document);
            lenient().when(hit.score()).thenReturn(0.9D);
            return hit;
        }).toList();
        HitsMetadata<KeywordDocument> metadata = org.mockito.Mockito.mock(HitsMetadata.class);
        when(metadata.hits()).thenReturn(hits);
        SearchResponse<KeywordDocument> response = org.mockito.Mockito.mock(SearchResponse.class);
        when(response.hits()).thenReturn(metadata);
        return response;
    }
}
