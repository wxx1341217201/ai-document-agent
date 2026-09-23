package com.wxx.aidocumentagent.retrieval;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.retrieval.rerank.RerankingApplicationService;
import com.wxx.aidocumentagent.vector.VectorIndex;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为阻塞型索引端口提供隔离的虚拟线程执行器，避免占用请求线程等待下游服务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.retrieval", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfiguration {

    @Bean(name = "hybridRetrievalExecutor", destroyMethod = "close")
    ExecutorService hybridRetrievalExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    HybridRetriever hybridRetriever(VectorIndex vectorIndex, KeywordIndex keywordIndex, RetrievalProperties properties,
                                    @Qualifier("hybridRetrievalExecutor") ExecutorService hybridRetrievalExecutor,
                                    RerankingApplicationService rerankingApplicationService) {
        return new HybridRetriever(vectorIndex, keywordIndex, properties, hybridRetrievalExecutor,
                rerankingApplicationService);
    }
}
