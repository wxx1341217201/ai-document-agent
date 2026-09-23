package com.wxx.aidocumentagent.retrieval.rerank;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 装配本地 NoOp、可选 HTTP 实现和进程内熔断器；默认选择不访问网络的实现。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RerankingProperties.class)
public class RerankingConfiguration {

    @Bean(name = "noOpReranker")
    NoOpReranker noOpReranker() {
        return new NoOpReranker();
    }

    @Bean(name = "httpCrossEncoderReranker")
    @ConditionalOnProperty(prefix = "app.rerank", name = "enabled", havingValue = "true")
    HttpCrossEncoderReranker httpCrossEncoderReranker(RerankingProperties properties) {
        return new HttpCrossEncoderReranker(properties);
    }

    @Bean(name = "reranker")
    @Primary
    Reranker reranker(RerankingProperties properties, @Qualifier("noOpReranker") Reranker noOpReranker,
                      ObjectProvider<HttpCrossEncoderReranker> httpCrossEncoderReranker) {
        if (!properties.isEnabled()) {
            return noOpReranker;
        }
        HttpCrossEncoderReranker httpReranker = httpCrossEncoderReranker.getIfAvailable();
        if (httpReranker == null) {
            return noOpReranker;
        }
        return new CircuitBreakingReranker(httpReranker, properties);
    }

    @Bean
    RerankingApplicationService rerankingApplicationService(RerankingProperties properties,
                                                              @Qualifier("reranker") Reranker reranker,
                                                              @Qualifier("noOpReranker") Reranker noOpReranker) {
        return new RerankingApplicationService(properties, reranker, noOpReranker);
    }
}
