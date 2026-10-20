package com.wxx.aidocumentagent.agent.react;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.application.RagQueryRetrievalService;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;
import jakarta.validation.Validator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReActConfiguration {

    @Bean(name = "reactExecutor", destroyMethod = "close")
    ExecutorService reactExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    ToolRegistry reactToolRegistry(RagQueryRetrievalService retrieval, DocumentRepository documents,
                                   DocumentChunkRepository chunks, ReActProperties properties,
                                   ObjectMapper mapper, Validator validator) {
        return new ToolRegistry(List.of(new SearchKnowledgeBaseTool(retrieval, documents, chunks, properties),
                new GetDocumentMetadataTool(documents), new GetChunkContextTool(chunks, documents,
                properties.getMaxChunkCharacters())), mapper, validator);
    }

    @Bean
    ReActModelClient reactModelClient(@Qualifier("ragSpringAiChatClient") ChatClient client,
                                     ReActProperties properties, RagChatOptions options) {
        return new SpringAiReActModelClient(client, properties, options);
    }

    @Bean
    ReActAgent reactAgent(KnowledgeBaseRepository knowledgeBases, ConversationRepository conversations,
                         ReActModelClient model, ToolRegistry registry, ReActProperties properties,
                         @Qualifier("reactExecutor") ExecutorService executor, ObjectMapper mapper) {
        return new ReActAgent(knowledgeBases, conversations, model, registry, properties, executor, mapper);
    }
}
