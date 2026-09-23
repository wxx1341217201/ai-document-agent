package com.wxx.aidocumentagent.rag.application;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.chunking.TextChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagContextBuilderTest {

    private static final long KNOWLEDGE_BASE_ID = 7L;

    @Test
    void 优先保留来源多样性并去除同文档连续重叠chunk() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        Document documentOne = document(101L, "architecture.md");
        Document documentTwo = document(202L, "operations.md");
        String shared = "z".repeat(100);
        DocumentChunk first = chunk(1_001L, 101L, 0, "前半段" + shared, 1, 1);
        DocumentChunk overlappingNext = chunk(1_002L, 101L, 1, shared + "后半段", 1, 1);
        DocumentChunk otherSource = chunk(2_001L, 202L, 0, "来自另一份文档的高价值证据。", 2, 2);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(first, overlappingNext, otherSource));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(documentOne, documentTwo));

        RagContext context = new RagContextBuilder(documents, chunks, properties()).build(KNOWLEDGE_BASE_ID, "问题？",
                List.of(hit(1_001L, 101L), hit(1_002L, 101L), hit(2_001L, 202L)));

        assertThat(context.citations()).extracting(RagCitation::documentId).containsExactly(101L, 202L);
        assertThat(context.citations()).extracting(RagCitation::chunkId).containsExactly(1_001L, 2_001L);
        assertThat(context.userPrompt()).contains("[C1]", "[C2]", "architecture.md", "operations.md");
    }

    @Test
    void 在字符预算内截断证据但保持程序生成的引用映射() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        String content = "A".repeat(1_000);
        Document document = document(101L, "large.txt");
        DocumentChunk chunk = chunk(1_001L, 101L, 0, content, 3, 3);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection())).thenReturn(List.of(chunk));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection())).thenReturn(List.of(document));
        RagProperties properties = properties();
        properties.setMaxContextCharacters(400);
        properties.setMaxChunkCharacters(1_000);
        properties.setMinChunkCharacters(100);
        properties.setQuoteMaxCharacters(50);

        RagContext context = new RagContextBuilder(documents, chunks, properties).build(KNOWLEDGE_BASE_ID, "问题？",
                List.of(hit(1_001L, 101L)));

        assertThat(context.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.citationId()).isEqualTo("C1");
            assertThat(citation.chunkId()).isEqualTo(1_001L);
            assertThat(citation.quote()).isEqualTo("A".repeat(50));
        });
        assertThat(context.userPrompt()).contains("[C1]");
        assertThat(context.userPrompt().length()).isLessThan(content.length());
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.setContextWindowTokens(8_192);
        properties.setReservedOutputTokens(1_024);
        properties.setMaxContextCharacters(4_000);
        properties.setMaxChunkCharacters(1_000);
        properties.setMinChunkCharacters(20);
        properties.setQuoteMaxCharacters(500);
        properties.setRedundantOverlapCharacters(80);
        return properties;
    }

    private Document document(long id, String name) {
        Document result = Document.uploaded(KNOWLEDGE_BASE_ID, name, "storage-" + id, "text/plain", "txt", 1L,
                "a".repeat(64));
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private DocumentChunk chunk(long id, long documentId, int chunkIndex, String content, int pageFrom, int pageTo) {
        DocumentChunk result = DocumentChunk.create(KNOWLEDGE_BASE_ID, documentId,
                new TextChunk(chunkIndex, content, 10, pageFrom, pageTo, null, Map.of()));
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private RetrievedChunk hit(long chunkId, long documentId) {
        return new RetrievedChunk(chunkId, KNOWLEDGE_BASE_ID, documentId, "index text", 1, 1, 1, 0.9D,
                null, null, 0.01D, List.of(RetrievalChannel.VECTOR));
    }
}
