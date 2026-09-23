package com.wxx.aidocumentagent.rag;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.openai.core.Timeout;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.application.CitationValidator;
import com.wxx.aidocumentagent.rag.application.RagChatClient;
import com.wxx.aidocumentagent.rag.application.RagContextBuilder;
import com.wxx.aidocumentagent.rag.application.RagModelCallAuditService;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagRetrievalGateway;
import com.wxx.aidocumentagent.rag.infrastructure.chat.SpringAiRagChatClient;
import com.wxx.aidocumentagent.rag.infrastructure.persistence.RagModelCallAuditRepository;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import com.wxx.aidocumentagent.retrieval.RetrievalQuery;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 M11 问答链路，并以 Spring AI 官方 OpenAI HTTP 定制点设置连接、读取和请求超时。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RagProperties.class)
public class RagConfiguration {

    @Bean(name = "ragChatExecutor", destroyMethod = "close")
    ExecutorService ragChatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    OpenAiHttpClientBuilderCustomizer ragOpenAiHttpClientTimeoutCustomizer(RagProperties properties) {
        return builder -> builder.timeout(Timeout.builder()
                .connect(properties.getConnectTimeout())
                .read(properties.getReadTimeout())
                .write(properties.getReadTimeout())
                .request(properties.getChatTimeout())
                .build());
    }

    @Bean(name = "ragSpringAiChatClient")
    ChatClient ragSpringAiChatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean
    RagChatClient ragChatClient(@Qualifier("ragSpringAiChatClient") ChatClient chatClient,
                                @Qualifier("ragChatExecutor") ExecutorService ragChatExecutor,
                                RagProperties properties) {
        return new SpringAiRagChatClient(chatClient, ragChatExecutor, properties);
    }

    @Bean
    RagRetrievalGateway ragRetrievalGateway(HybridRetriever hybridRetriever) {
        return (knowledgeBaseId, question, topK, rerank) -> hybridRetriever.retrieve(
                new RetrievalQuery(knowledgeBaseId, question, topK, topK, topK), rerank);
    }

    @Bean(name = "ragTokenCountEstimator")
    TokenCountEstimator ragTokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    @Bean
    RagContextBuilder ragContextBuilder(DocumentRepository documentRepository,
                                        DocumentChunkRepository documentChunkRepository,
                                        RagProperties properties,
                                        @Qualifier("ragTokenCountEstimator") TokenCountEstimator tokenCountEstimator) {
        return new RagContextBuilder(documentRepository, documentChunkRepository, properties, tokenCountEstimator);
    }

    @Bean
    CitationValidator citationValidator() {
        return new CitationValidator();
    }

    @Bean
    RagModelCallAuditService ragModelCallAuditService(RagModelCallAuditRepository repository) {
        return new RagModelCallAuditService(repository);
    }

    @Bean
    RagQueryApplicationService ragQueryApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                                           RagRetrievalGateway retrievalGateway,
                                                           RagContextBuilder contextBuilder,
                                                           RagChatClient chatClient,
                                                           CitationValidator citationValidator,
                                                           RagModelCallAuditService auditService,
                                                           RagProperties properties) {
        return new RagQueryApplicationService(knowledgeBaseRepository, retrievalGateway, contextBuilder, chatClient,
                citationValidator, auditService, properties);
    }
}
