package com.wxx.aidocumentagent.retrieval.rerank;

import java.net.URI;
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

class CohereRerankerTest {

    private RerankingProperties properties;
    private MockRestServiceServer server;
    private HttpCrossEncoderReranker reranker;

    @BeforeEach
    void setUp() {
        properties = new RerankingProperties();
        properties.setEnabled(true);
        properties.setProvider(RerankingProperties.Provider.COHERE);
        properties.setUrl(URI.create("https://api.cohere.com/v2/rerank"));
        properties.setModel("rerank-v4.0-fast");
        properties.setApiKey("test-key");
        properties.setMaxRetries(0);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        reranker = new HttpCrossEncoderReranker(properties, builder.build());
    }

    @AfterEach
    void tearDown() {
        server.verify();
    }

    @Test
    void 按Cohere协议发送请求并按零起始位置映射回原始Chunk() {
        properties.setMaxTextLength(4);
        server.expect(requestTo(properties.getUrl().toString()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("rerank-v4.0-fast"))
                .andExpect(jsonPath("$.query").value("retry"))
                .andExpect(jsonPath("$.top_n").value(2))
                .andExpect(jsonPath("$.documents.length()").value(3))
                .andExpect(jsonPath("$.documents[0]").value("abcd"))
                .andExpect(jsonPath("$.documents[1]").value("efgh"))
                .andExpect(jsonPath("$.documents[2]").value("ijkl"))
                .andRespond(withSuccess("""
                        {"results":[{"index":2,"relevance_score":0.9},
                                    {"index":0,"relevance_score":0.2}],"id":"response-id"}
                        """, MediaType.APPLICATION_JSON));

        List<RankedChunk> result = reranker.rerank("retry", List.of(chunk(101, "abcdef"),
                chunk(102, "efghij"), chunk(103, "ijklmn")), 2);

        assertThat(result).extracting(RankedChunk::chunkId).containsExactly(103L, 101L);
        assertThat(result).extracting(RankedChunk::rrfRank).containsExactly(3, 1);
        assertThat(result).extracting(RankedChunk::rerankScore).containsExactly(0.9D, 0.2D);
        assertThat(result.getFirst().chunk().content()).isEqualTo("ijklmn");
    }

    @Test
    void 重复位置响应被拒绝而不误绑定Chunk() {
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.9},
                                    {"index":0,"relevance_score":0.8}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> reranker.rerank("query", List.of(chunk(101, "first"),
                chunk(102, "second")), 2))
                .isInstanceOfSatisfying(RerankingException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RerankingErrorCode.INVALID_RESPONSE));
    }

    @Test
    void 认证错误不重试() {
        properties.setMaxRetries(1);
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> reranker.rerank("query", List.of(chunk(101, "first")), 1))
                .isInstanceOfSatisfying(RerankingException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RerankingErrorCode.REMOTE_REJECTED));
    }

    @Test
    void 限流响应最多按配置重试一次() {
        properties.setMaxRetries(1);
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(properties.getUrl().toString()))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.7}]}
                        """, MediaType.APPLICATION_JSON));

        List<RankedChunk> result = reranker.rerank("query", List.of(chunk(101, "first")), 1);

        assertThat(result).extracting(RankedChunk::chunkId).containsExactly(101L);
    }

    private RetrievedChunk chunk(long chunkId, String content) {
        return new RetrievedChunk(chunkId, 9L, chunkId + 100L, content, 1, 1, 1, 0.8D,
                null, null, 0.01D, List.of(RetrievalChannel.VECTOR));
    }
}
