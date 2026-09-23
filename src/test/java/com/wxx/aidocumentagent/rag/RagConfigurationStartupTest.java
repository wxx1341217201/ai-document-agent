package com.wxx.aidocumentagent.rag;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.application.RagChatClient;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.infrastructure.persistence.RagModelCallAuditRepository;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅验证生产装配，不触发数据库、检索索引或真实 OpenAI 请求。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.rag.enabled=true")
@ActiveProfiles("test")
class RagConfigurationStartupTest {

    @MockitoBean
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @MockitoBean
    private DocumentRepository documentRepository;

    @MockitoBean
    private DocumentChunkRepository documentChunkRepository;

    @MockitoBean
    private RagModelCallAuditRepository ragModelCallAuditRepository;

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
    @Qualifier("ragSpringAiChatClient")
    private ChatClient springAiChatClient;

    @Test
    void 问答链路可由SpringAIChatClient装配且测试中未发起外部调用() {
        assertThat(ragQueryApplicationService).isNotNull();
        assertThat(ragChatClient).isNotNull();
        assertThat(springAiChatClient).isNotNull();
    }
}
