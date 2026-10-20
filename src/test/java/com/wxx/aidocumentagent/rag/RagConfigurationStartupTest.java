package com.wxx.aidocumentagent.rag;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.react.ReActAgent;
import com.wxx.aidocumentagent.agent.react.ToolRegistry;
import com.wxx.aidocumentagent.agent.plan.PlanExecuteReflectAgent;
import com.wxx.aidocumentagent.conversation.application.ConversationApplicationService;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.application.RagChatClient;
import com.wxx.aidocumentagent.rag.application.RagRetrievalGateway;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RetrievalFirstAgent;
import com.wxx.aidocumentagent.rag.infrastructure.persistence.RagModelCallAuditRepository;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import com.wxx.aidocumentagent.retrieval.RetrievalProperties;
import com.wxx.aidocumentagent.retrieval.RetrievalQuery;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅验证生产装配，不触发数据库、检索索引或真实 OpenAI 请求。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.rag.enabled=true", "app.conversation.enabled=true"})
@ActiveProfiles("test")
@Import(RagConfigurationStartupTest.SharedClientConfiguration.class)
class RagConfigurationStartupTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class SharedClientConfiguration {
        @Bean
        ChatClientBuilderCustomizer sharedChatDefaults() {
            return builder -> builder.defaultSystem("Shared chat client configuration");
        }

        @Bean
        ClientObservationRecorder clientObservationRecorder() {
            return new ClientObservationRecorder();
        }
    }

    static class ClientObservationRecorder implements ObservationHandler<ChatClientObservationContext> {
        private final java.util.List<String> names = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ChatClientObservationContext;
        }

        @Override
        public void onStop(ChatClientObservationContext context) {
            names.add(context.getName());
        }
    }

    @Autowired
    private ClientObservationRecorder clientObservations;

    @MockitoBean
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @MockitoBean
    private DocumentRepository documentRepository;

    @MockitoBean
    private DocumentChunkRepository documentChunkRepository;

    @MockitoBean
    private RagModelCallAuditRepository ragModelCallAuditRepository;

    @MockitoBean
    private ConversationRepository conversationRepository;

    @MockitoBean
    private ConversationMessageRepository conversationMessageRepository;

    @MockitoBean
    private DocumentIngestionJobRepository documentIngestionJobRepository;

    @MockitoBean
    private DocumentBatchTaskRepository documentBatchTaskRepository;

    @MockitoBean
    private DocumentIngestionOutboxEventRepository documentIngestionOutboxEventRepository;

    @MockitoBean
    private HybridRetriever hybridRetriever;

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private RagQueryApplicationService ragQueryApplicationService;

    @Autowired
    private RagChatClient ragChatClient;

    @Autowired
    private RagRetrievalGateway ragRetrievalGateway;

    @Autowired
    private RetrievalProperties retrievalProperties;

    @Autowired
    private RetrievalFirstAgent retrievalFirstAgent;

    @Autowired
    private ReActAgent reActAgent;

    @Autowired
    private PlanExecuteReflectAgent planExecuteReflectAgent;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentRouter agentRouter;

    @Autowired
    private ConversationApplicationService conversationApplicationService;

    @Autowired
    @Qualifier("ragSpringAiChatClient")
    private ChatClient springAiChatClient;

    @Test
    void 问答客户端继承全局定制和官方观测() {
        org.mockito.Mockito.when(chatModel.getOptions())
                .thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().build());
        org.mockito.Mockito.when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new ChatResponse(java.util.List.of(new Generation(new AssistantMessage("pong")))));
        clientObservations.names.clear();

        assertThat(springAiChatClient.prompt().user("ping")
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)).call().content()).isEqualTo("pong");

        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText()).isEqualTo("Shared chat client configuration");
        assertThat(clientObservations.names).contains("spring.ai.chat.client");
    }

    @Test
    void 问答链路可由SpringAIChatClient装配且测试中未发起外部调用() {
        assertThat(ragQueryApplicationService).isNotNull();
        assertThat(ragChatClient).isNotNull();
        assertThat(springAiChatClient).isNotNull();
        assertThat(retrievalFirstAgent).isNotNull();
        assertThat(reActAgent).isNotNull();
        assertThat(planExecuteReflectAgent).isNotNull();
        assertThat(java.util.Arrays.stream(toolRegistry.advertisedCallbacks())
                .map(callback -> callback.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrder("searchKnowledgeBase", "getDocumentMetadata", "getChunkContext");
        assertThat(java.util.Arrays.stream(toolRegistry.advertisedCallbacks())
                .map(callback -> callback.getToolDefinition().inputSchema()).toList())
                .allSatisfy(schema -> assertThat(schema).doesNotContain("knowledgeBaseId"));
        assertThat(agentRouter).isNotNull();
        assertThat(conversationApplicationService).isNotNull();
    }

    @Test
    void 最终TopK不会压缩两路召回候选数() {
        int vector = retrievalProperties.getVectorTopK();
        int keyword = retrievalProperties.getKeywordTopK();
        try {
            retrievalProperties.setVectorTopK(40);
            retrievalProperties.setKeywordTopK(20);
            var query = new RetrievalQuery(7, "service limits", 40, 20, 8);
            org.mockito.Mockito.when(hybridRetriever.retrieve(query, false))
                    .thenReturn(new RetrievalResult(java.util.List.of(), false, java.util.List.of()));
            ragRetrievalGateway.retrieve(7, "service limits", 8, false);
            org.mockito.Mockito.verify(hybridRetriever).retrieve(query, false);
        }
        finally {
            retrievalProperties.setVectorTopK(vector);
            retrievalProperties.setKeywordTopK(keyword);
        }
    }
}
