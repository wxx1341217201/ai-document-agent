package com.wxx.aidocumentagent;

import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionJobRepository;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentIngestionOutboxEventRepository;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiDocumentAgentApplicationTests {

    @MockitoBean
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @MockitoBean
    private DocumentRepository documentRepository;

    @MockitoBean
    private DocumentChunkRepository documentChunkRepository;

    @MockitoBean
    private DocumentIngestionJobRepository documentIngestionJobRepository;

    @MockitoBean
    private DocumentBatchTaskRepository documentBatchTaskRepository;

    @MockitoBean
    private DocumentIngestionOutboxEventRepository documentIngestionOutboxEventRepository;

    @Test
    void 应用上下文可以加载() {
    }

}
