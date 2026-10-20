package com.wxx.aidocumentagent.rag;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.openai.core.Timeout;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.application.CitationValidator;
import com.wxx.aidocumentagent.rag.application.RagChatClient;
import com.wxx.aidocumentagent.rag.application.RagContextBuilder;
import com.wxx.aidocumentagent.rag.application.RagModelCallAuditService;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryRetrievalService;
import com.wxx.aidocumentagent.rag.application.RagRetrievalGateway;
import com.wxx.aidocumentagent.rag.application.ModelRetrievalQuestionRewriter;
import com.wxx.aidocumentagent.rag.application.RetrievalFirstAgent;
import com.wxx.aidocumentagent.rag.application.RetrievalQuestionRewriter;
import com.wxx.aidocumentagent.rag.infrastructure.chat.SpringAiRagChatClient;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;
import com.wxx.aidocumentagent.rag.infrastructure.persistence.RagModelCallAuditRepository;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import com.wxx.aidocumentagent.retrieval.RetrievalQuery;
import com.wxx.aidocumentagent.retrieval.RetrievalProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 装配 M11 问答链路，并以 Spring AI 官方 OpenAI HTTP 定制点设置连接、读取和请求超时。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({RagProperties.class, RetrievalProperties.class})
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
    ChatClient ragSpringAiChatClient(ChatClient.Builder builder, RagChatOptions options) {
        return builder.defaultOptions(options.options(null, false)).build();
    }

    @Bean
    RagChatOptions ragChatOptions(RagProperties properties, Environment environment) {
        return new RagChatOptions(environment.getProperty("spring.ai.openai.chat.base-url",
                environment.getProperty("spring.ai.openai.base-url", "https://api.openai.com")),
                properties.isDisableThinking());
    }

    @Bean
    RagChatClient ragChatClient(@Qualifier("ragSpringAiChatClient") ChatClient chatClient,
                                @Qualifier("ragChatExecutor") ExecutorService ragChatExecutor,
                                RagProperties properties) {
        return new SpringAiRagChatClient(chatClient, ragChatExecutor, properties);
    }

    @Bean
    RagRetrievalGateway ragRetrievalGateway(HybridRetriever hybridRetriever, RetrievalProperties retrievalProperties) {
        return (knowledgeBaseId, question, topK, rerank) -> hybridRetriever.retrieve(
                new RetrievalQuery(knowledgeBaseId, question, Math.max(topK, retrievalProperties.getVectorTopK()),
                        Math.max(topK, retrievalProperties.getKeywordTopK()), topK), rerank);
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
                                                           RagProperties properties,
                                                           RagQueryRetrievalService queryRetrievalService) {
        return new RagQueryApplicationService(knowledgeBaseRepository, retrievalGateway, contextBuilder, chatClient,
                citationValidator, auditService, properties, queryRetrievalService);
    }

    @Bean
    RagQueryRetrievalService ragQueryRetrievalService(RagRetrievalGateway gateway, RagProperties properties,
                                                     @Qualifier("ragChatExecutor") ExecutorService executor) {
        return new RagQueryRetrievalService(gateway, properties, executor);
    }

    @Bean
    RetrievalQuestionRewriter retrievalQuestionRewriter(RagChatClient chatClient) {
        return new ModelRetrievalQuestionRewriter(chatClient);
    }

    @Bean
    RetrievalFirstAgent retrievalFirstAgent(KnowledgeBaseRepository knowledgeBaseRepository,
                                           ConversationRepository conversationRepository,
                                           RagRetrievalGateway retrievalGateway,
                                           RagContextBuilder contextBuilder,
                                           RagChatClient chatClient,
                                           RetrievalQuestionRewriter rewriter,
                                           CitationValidator citationValidator,
                                           RagModelCallAuditService auditService,
                                           RagProperties ragProperties,
                                           RetrievalFirstProperties properties,
                                           @Qualifier("ragChatExecutor") ExecutorService executor) {
        return new RetrievalFirstAgent(knowledgeBaseRepository, conversationRepository, retrievalGateway,
                contextBuilder, chatClient,
                rewriter, citationValidator, auditService, ragProperties, properties, executor);
    }
}
