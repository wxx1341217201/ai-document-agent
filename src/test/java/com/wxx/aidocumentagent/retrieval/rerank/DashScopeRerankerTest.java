package com.wxx.aidocumentagent.retrieval.rerank;

import java.net.URI;
import java.util.List;

import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashScopeRerankerTest {
    private RerankingProperties properties;
    private MockRestServiceServer server;
    private HttpCrossEncoderReranker reranker;

    @BeforeEach
    void setUp() {
        properties = new RerankingProperties();
        properties.setEnabled(true);
        properties.setProvider(RerankingProperties.Provider.DASHSCOPE);
        properties.setUrl(URI.create("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"));
        properties.setModel("gte-rerank-v2");
        properties.setApiKey("test-key");
        properties.setMaxRetries(0);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        reranker = new HttpCrossEncoderReranker(properties, builder.build());
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void 按DashScope嵌套协议发送请求并映射候选索引() {
        properties.setMaxCandidates(3);
        properties.setMaxTextLength(4);
        server.expect(requestTo(properties.getUrl().toString()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("gte-rerank-v2"))
                .andExpect(jsonPath("$.input.query").value("重试"))
                .andExpect(jsonPath("$.input.documents.length()").value(3))
                .andExpect(jsonPath("$.input.documents[0]").value("abcd"))
                .andExpect(jsonPath("$.input.documents[2]").value("ijkl"))
                .andExpect(jsonPath("$.parameters.top_n").value(2))
                .andExpect(jsonPath("$.parameters.return_documents").value(false))
                .andRespond(withSuccess("""
                        {"output":{"results":[{"index":0,"relevance_score":0.2},
                                              {"index":2,"relevance_score":0.9}]},
                         "usage":{"total_tokens":42},"request_id":"not-a-chunk-id"}
                        """, MediaType.APPLICATION_JSON));

        var result = reranker.rerank("重试", List.of(chunk(101, "abcdef"), chunk(102, "efghij"),
                chunk(103, "ijklmn"), chunk(104, "outside bound")), 2);

        assertThat(result).extracting(RankedChunk::chunkId).containsExactly(103L, 101L);
        assertThat(result).extracting(RankedChunk::rrfRank).containsExactly(3, 1);
        assertThat(result).extracting(RankedChunk::rerankScore).containsExactly(0.9D, 0.2D);
        assertThat(result.getFirst().chunk().content()).isEqualTo("ijklmn");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}",
            "{\"output\":{\"results\":[]}}",
            "{\"output\":{\"results\":[{\"index\":-1,\"relevance_score\":0.5},{\"index\":1,\"relevance_score\":0.4}]}}",
            "{\"output\":{\"results\":[{\"index\":2,\"relevance_score\":0.5},{\"index\":1,\"relevance_score\":0.4}]}}",
            "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":0,\"relevance_score\":0.4}]}}",
            "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":null},{\"index\":1,\"relevance_score\":0.4}]}}"
    })
    void 拒绝缺失输出和错位标签(String response) {
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> reranker.rerank("query", List.of(chunk(101, "first"), chunk(102, "second")), 2))
                .isInstanceOfSatisfying(RerankingException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RerankingErrorCode.INVALID_RESPONSE));
    }

    @Test
    void 限流按配置重试并丢弃供应商错误正文() {
        properties.setMaxRetries(1);
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("sensitive provider body"));
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withSuccess("""
                        {"output":{"results":[{"index":0,"relevance_score":0.7}]}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(reranker.rerank("query", List.of(chunk(101, "first")), 5))
                .extracting(RankedChunk::chunkId).containsExactly(101L);
    }

    private RetrievedChunk chunk(long id, String content) {
        return new RetrievedChunk(id, 9L, id + 100L, content, 1, 1, 1, 0.8D,
                null, null, 0.01D, List.of(RetrievalChannel.VECTOR));
    }
}
