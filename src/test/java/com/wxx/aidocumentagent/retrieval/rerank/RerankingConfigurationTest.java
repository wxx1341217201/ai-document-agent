package com.wxx.aidocumentagent.retrieval.rerank;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RerankingConfigurationTest {

    @Test
    void 重排客户端继承全局定制并仅覆盖自身超时() {
        var settingsUsed = new AtomicReference<HttpClientSettings>();
        var requestUsed = new AtomicReference<MockClientHttpRequest>();
        ClientHttpRequestFactoryBuilder<ClientHttpRequestFactory> factories = settings -> {
            settingsUsed.set(settings);
            return (uri, method) -> {
                var request = new MockClientHttpRequest(method, uri);
                var response = new MockClientHttpResponse(
                        "{\"results\":[{\"id\":\"101\",\"score\":0.9}]}".getBytes(StandardCharsets.UTF_8),
                        HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                request.setResponse(response);
                requestUsed.set(request);
                return request;
            };
        };
        contextRunner.withPropertyValues("app.rerank.enabled=true", "app.rerank.url=https://rerank.test/rerank",
                        "app.rerank.connect-timeout=321ms", "app.rerank.read-timeout=654ms",
                        "spring.http.clients.redirects=dont-follow")
                .withBean(ClientHttpRequestFactoryBuilder.class, () -> factories)
                .withBean(RestClientCustomizer.class,
                        () -> builder -> builder.defaultHeader("X-Shared-Client", "configured"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var candidate = new RetrievedChunk(101, 1, 1, "content",
                            null, null, 1, 0.9, null, null, 0.1,
                            List.of(RetrievalChannel.VECTOR));
                    var result = context.getBean(HttpCrossEncoderReranker.class)
                            .rerank("query", List.of(candidate), 1);
                    assertThat(result).extracting(RankedChunk::chunkId).containsExactly(101L);
                    assertThat(requestUsed.get().getHeaders().getFirst("X-Shared-Client")).isEqualTo("configured");
                    assertThat(settingsUsed.get().connectTimeout()).isEqualTo(Duration.ofMillis(321));
                    assertThat(settingsUsed.get().readTimeout()).isEqualTo(Duration.ofMillis(654));
                    assertThat(settingsUsed.get().redirects())
                            .isEqualTo(HttpRedirects.DONT_FOLLOW);
                });
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(RerankingConfiguration.class);

    @Test
    void Cohere配置从环境属性绑定并选择远端重排器() {
        contextRunner.withPropertyValues(
                "app.rerank.enabled=true",
                "app.rerank.provider=cohere",
                "app.rerank.url=https://api.cohere.com/v2/rerank",
                "app.rerank.model=rerank-v4.0-fast",
                "app.rerank.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RerankingProperties.class).getProvider())
                            .isEqualTo(RerankingProperties.Provider.COHERE);
                    assertThat(context.getBean("reranker")).isInstanceOf(CircuitBreakingReranker.class);
                });
    }

    @Test
    void Cohere缺少密钥时启动失败() {
        contextRunner.withPropertyValues(
                "app.rerank.enabled=true",
                "app.rerank.provider=cohere",
                "app.rerank.url=https://api.cohere.com/v2/rerank",
                "app.rerank.model=rerank-v4.0-fast")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void DashScope配置绑定并启用远端重排() {
        contextRunner.withPropertyValues(
                "app.rerank.enabled=true", "app.rerank.provider=dashscope",
                "app.rerank.url=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
                "app.rerank.model=gte-rerank-v2", "app.rerank.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RerankingProperties.class).getProvider())
                            .isEqualTo(RerankingProperties.Provider.DASHSCOPE);
                    assertThat(context.getBean("reranker")).isInstanceOf(CircuitBreakingReranker.class);
                });
    }

    @Test
    void DashScope缺少模型或密钥时启动失败() {
        contextRunner.withPropertyValues("app.rerank.enabled=true", "app.rerank.provider=dashscope",
                "app.rerank.url=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank")
                .run(context -> assertThat(context).hasFailed());
    }
}
