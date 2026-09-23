package com.wxx.aidocumentagent.keyword;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingProcessor;
import com.wxx.aidocumentagent.ingestion.application.StagedChunkBatchIndexingProcessor;
import com.wxx.aidocumentagent.keyword.application.KeywordChunkBatchIndexingProcessor;
import com.wxx.aidocumentagent.keyword.infrastructure.DocumentChunksIndexInitializer;
import com.wxx.aidocumentagent.keyword.infrastructure.ElasticsearchKeywordIndex;
import com.wxx.aidocumentagent.keyword.infrastructure.PreemptiveBasicAuthenticationRest5ClientCustomizer;
import com.wxx.aidocumentagent.vector.application.VectorChunkBatchIndexingProcessor;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchProperties;
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** M08 使用 Spring Boot 提供的 Elasticsearch Java Client，不自行管理 HTTP 客户端。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.keyword", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KeywordProperties.class)
public class KeywordConfiguration {

    @Bean
    Rest5ClientBuilderCustomizer elasticsearchBasicAuthenticationCustomizer(ElasticsearchProperties properties) {
        return new PreemptiveBasicAuthenticationRest5ClientCustomizer(properties.getUsername(), properties.getPassword());
    }

    @Bean
    DocumentChunksIndexInitializer documentChunksIndexInitializer(ElasticsearchClient client, KeywordProperties properties,
                                                                   KeywordIndexExceptionTranslator exceptionTranslator) {
        return new DocumentChunksIndexInitializer(client, properties, exceptionTranslator);
    }

    @Bean
    ApplicationRunner documentChunksIndexInitializationRunner(DocumentChunksIndexInitializer initializer) {
        return arguments -> initializer.initialize();
    }

    @Bean
    KeywordIndex keywordIndex(ElasticsearchClient client, KeywordProperties properties,
                              KeywordIndexExceptionTranslator exceptionTranslator) {
        return new ElasticsearchKeywordIndex(client, properties, exceptionTranslator);
    }

    @Bean
    KeywordChunkBatchIndexingProcessor keywordChunkBatchIndexingProcessor(KeywordIndex keywordIndex) {
        return new KeywordChunkBatchIndexingProcessor(keywordIndex);
    }

    /** 两个必需阶段均存在才暴露实际 worker；缺少向量阶段时启动失败而不是静默漏索引。 */
    @Bean
    ChunkBatchIndexingProcessor chunkBatchIndexingProcessor(VectorChunkBatchIndexingProcessor vectorProcessor,
                                                              KeywordChunkBatchIndexingProcessor keywordProcessor) {
        return new StagedChunkBatchIndexingProcessor(vectorProcessor, keywordProcessor);
    }
}
