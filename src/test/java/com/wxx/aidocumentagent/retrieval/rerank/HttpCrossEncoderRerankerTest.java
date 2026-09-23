package com.wxx.aidocumentagent.retrieval.rerank;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.List;

import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpCrossEncoderRerankerTest {

    private RerankingProperties properties;
    private MockRestServiceServer server;
    private HttpCrossEncoderReranker reranker;

    @BeforeEach
    void setUp() {
        properties = enabledProperties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        reranker = new HttpCrossEncoderReranker(properties, builder.build());
    }

    @AfterEach
    void tearDown() {
        server.verify();
    }

    @Test
    void 批量按Id关联分数限制候选并只截断外发文本() {
        properties.setMaxCandidates(2);
        properties.setMaxTextLength(4);
        properties.setMaxRetries(0);
        String response = """
                {"results":[{"id":"102","score":0.10},{"id":"101","score":0.90}]}
                """;
        server.expect(requestTo(properties.getUrl().toString()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-rerank-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.query").value("how does retry work"))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[0].id").value("101"))
                .andExpect(jsonPath("$.documents[0].text").value("abcd"))
                .andExpect(jsonPath("$.documents[1].id").value("102"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        RetrievedChunk first = chunk(101L, "abcdef");
        RetrievedChunk second = chunk(102L, "uvwxyz");
        List<RankedChunk> result = reranker.rerank("how does retry work", List.of(first, second,
                chunk(103L, "not sent")), 3);

        assertThat(result).extracting(RankedChunk::chunkId).containsExactly(101L, 102L);
        assertThat(result).extracting(RankedChunk::rrfRank).containsExactly(1, 2);
        assertThat(result).extracting(RankedChunk::rerankRank).containsExactly(1, 2);
        assertThat(result).extracting(RankedChunk::rerankScore).containsExactly(0.90D, 0.10D);
        assertThat(result.getFirst().chunk().content()).isEqualTo("abcdef");
        assertThat(second.content()).isEqualTo("uvwxyz");
    }

    @Test
    void 相同分数按原始Rrf顺序稳定排序而不是响应数组顺序() {
        properties.setMaxRetries(0);
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withSuccess("""
                        {"results":[{"id":"102","score":0.7},{"id":"101","score":0.7}]}
                        """, MediaType.APPLICATION_JSON));

        List<RankedChunk> result = reranker.rerank("query", List.of(chunk(101L, "first"), chunk(102L, "second")), 2);

        assertThat(result).extracting(RankedChunk::chunkId).containsExactly(101L, 102L);
        assertThat(result).extracting(RankedChunk::rerankRank).containsExactly(1, 2);
    }

    @Test
    void 不完整或未知Id响应被拒绝而非按位置错误绑定() {
        properties.setMaxRetries(0);
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withSuccess("""
                        {"results":[{"id":"101","score":0.8},{"id":"foreign","score":0.9}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> reranker.rerank("query", List.of(chunk(101L, "first"), chunk(102L, "second")), 2))
                .isInstanceOfSatisfying(RerankingException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RerankingErrorCode.INVALID_RESPONSE));
    }

    @Test
    void 仅对可重试的服务不可用错误执行受限重试() {
        properties.setMaxRetries(1);
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> reranker.rerank("query", List.of(chunk(101L, "first")), 1))
                .isInstanceOfSatisfying(RerankingException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RerankingErrorCode.REMOTE_UNAVAILABLE));
    }

    @Test
    void mock超时被映射为安全超时错误() {
        properties.setMaxRetries(0);
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated read timeout");
                });

        assertThatThrownBy(() -> reranker.rerank("query", List.of(chunk(101L, "first")), 1))
                .isInstanceOfSatisfying(RerankingException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RerankingErrorCode.REQUEST_TIMEOUT));
    }

    private RerankingProperties enabledProperties() {
        RerankingProperties result = new RerankingProperties();
        result.setEnabled(true);
        result.setUrl(URI.create("https://reranker.example.test/v1/rerank"));
        result.setApiKey("test-rerank-key");
        return result;
    }

    private RetrievedChunk chunk(long chunkId, String content) {
        return new RetrievedChunk(chunkId, 9L, chunkId + 100L, content, 1, 1, 1, 0.8D,
                null, null, 0.01D, List.of(RetrievalChannel.VECTOR));
    }
}
