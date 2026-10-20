package com.wxx.aidocumentagent.rag.application;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.chunking.TextChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.domain.RagErrorCode;
import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagContextBuilderTest {

    private static final long KNOWLEDGE_BASE_ID = 7L;

    @Test
    void previouslyReadyGarbledChunksProduceAnActionableError() {
        var documents = mock(DocumentRepository.class);
        var chunks = mock(DocumentChunkRepository.class);
        var broken = chunk(1_001L, 101L, 0, "\u0002\u0003\u0004\n".repeat(50), 2, 67);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection())).thenReturn(List.of(broken));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(document(101L, "book.pdf")));

        assertThatThrownBy(() -> new RagContextBuilder(documents, chunks, properties())
                .build(KNOWLEDGE_BASE_ID, "贾宝玉是谁？", List.of(hit(1_001L, 101L))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RagErrorCode.UNREADABLE_EVIDENCE));
    }

    @Test
    void garbledCandidatesDoNotDisplaceReadableEvidence() {
        var documents = mock(DocumentRepository.class);
        var chunks = mock(DocumentChunkRepository.class);
        var broken = chunk(1_001L, 101L, 0, "\u0002\u0003\u0004\n".repeat(50), 2, 67);
        var readable = chunk(1_002L, 101L, 1, "贾宝玉是贾政与王夫人的儿子。".repeat(3), 68, 68);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(broken, readable));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(document(101L, "book.pdf")));

        var context = new RagContextBuilder(documents, chunks, properties()).build(KNOWLEDGE_BASE_ID,
                "贾宝玉是谁？", List.of(hit(1_001L, 101L), hit(1_002L, 101L)));
        assertThat(context.citations()).extracting(RagCitation::chunkId).containsExactly(1_002L);
        assertThat(context.userPrompt()).contains("贾政与王夫人").doesNotContain("\u0002");
    }

    @Test
    void brokenNamedDocumentCannotFallBackToAnUnrelatedReadableDocument() {
        var documents = mock(DocumentRepository.class);
        var chunks = mock(DocumentChunkRepository.class);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection())).thenReturn(List.of(
                chunk(1_001L, 101L, 0, "\u0002\u0003\u0004\n".repeat(50), 2, 67),
                chunk(2_001L, 202L, 0, "An unrelated readable document.".repeat(3), 1, 1)));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(document(101L, "broken.pdf"), document(202L, "unrelated.txt")));

        assertThatThrownBy(() -> new RagContextBuilder(documents, chunks, properties()).build(KNOWLEDGE_BASE_ID,
                "What does broken say?", List.of(hit(1_001L, 101L), hit(2_001L, 202L))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RagErrorCode.UNREADABLE_EVIDENCE));
    }

    @Test
    void aCombinedComparisonKeepsTheOtherDocumentWhenOnlyOneNameMatchesAFile() {
        var documents = mock(DocumentRepository.class);
        var chunks = mock(DocumentChunkRepository.class);
        var api = document(101, "api.txt");
        var operations = document(202, "operations.txt");
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(7L), anyCollection())).thenReturn(List.of(api, operations));
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(7L), anyCollection())).thenReturn(List.of(
                chunk(1001, 101, 0, "API latency is 100 milliseconds.", 1, 1),
                chunk(2001, 202, 0, "Worker throughput is 20 tasks per second.", 1, 1)));
        var context = new RagContextBuilder(documents, chunks, properties()).build(7,
                "Compare API latency and worker throughput", List.of(hit(1001, 101), hit(2001, 202)));
        assertThat(context.citations()).extracting(RagCitation::documentId).containsExactly(101L, 202L);
    }

    @Test
    void titleAndAuthorUseTheReadyDocumentHeaderRatherThanAnUploaderOrUnrelatedBook() {
        var documents = mock(DocumentRepository.class);
        var chunks = mock(DocumentChunkRepository.class);
        var manual = document(101, "deployment-guide.txt");
        var other = document(202, "other-book.txt");
        var narrative = chunk(1002, 101, 2, "deployment runtime steps", 1, 1);
        var header = chunk(1001, 101, 0, "Title: Deployment Guide\nAuthor: Verified Writer", 1, 1);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(7L), anyCollection())).thenReturn(List.of(narrative, chunk(2001, 202, 0, "other book", 1, 1)));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(7L), anyCollection())).thenReturn(List.of(manual, other));
        when(chunks.findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(7, 101, 0, 1)).thenReturn(List.of(header));
        var context = new RagContextBuilder(documents, chunks, properties()).build(7,
                "What are the title and author of the deployment guide?", List.of(hit(1002, 101), hit(2001, 202)));
        assertThat(context.evidence().getFirst().contextText()).contains("Author: Verified Writer");
        assertThat(context.citations()).extracting(RagCitation::documentId).containsOnly(101L);
        ReflectionTestUtils.setField(manual, "status", com.wxx.aidocumentagent.document.domain.DocumentStatus.UPLOADED);
        assertThat(new RagContextBuilder(documents, chunks, properties()).build(7, "deployment author", List.of(hit(1002, 101))).evidence()).isEmpty();
    }

    @Test
    void 优先保留来源多样性并只去除已完整包含的重复chunk() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        Document documentOne = document(101L, "architecture.md");
        Document documentTwo = document(202L, "operations.md");
        String shared = "z".repeat(100);
        DocumentChunk first = chunk(1_001L, 101L, 0, "前半段" + shared, 1, 1);
        DocumentChunk overlappingNext = chunk(1_002L, 101L, 1, shared, 1, 1);
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
    void 相邻chunk共享边界但具有独立事实时同时保留() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        String shared = "共同背景内容。".repeat(40);
        var first = chunk(1_001L, 101L, 0, "第一项事实：读取必须带知识库范围。" + shared, 1, 1);
        var next = chunk(1_002L, 101L, 1, shared + "第二项独立事实：失败任务最多重试三次并进入死信队列。", 1, 1);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection())).thenReturn(List.of(first, next));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(document(101L, "guide.txt")));
        var config = properties();
        config.setMinChunkCharacters(200);
        var context = new RagContextBuilder(documents, chunks, config).build(KNOWLEDGE_BASE_ID,
                "读取范围和失败重试分别有什么要求？", List.of(hit(1_001L, 101L), hit(1_002L, 101L)));
        assertThat(context.citations()).extracting(RagCitation::chunkId).containsExactly(1_001L, 1_002L);
        assertThat(context.userPrompt()).contains("最多重试三次", "读取必须带知识库范围");
    }

    @Test
    void 默认预算保留长chunk尾部事实且不突破实际token窗口() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        String content = "Background text. ".repeat(220) + "The final deadline is 72 hours.";
        var source = chunk(1_001L, 101L, 0, content, 1, 1);
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection())).thenReturn(List.of(source));
        when(documents.findByKnowledgeBaseIdAndIdIn(eq(KNOWLEDGE_BASE_ID), anyCollection()))
                .thenReturn(List.of(document(101L, "guide.txt")));
        var properties = new RagProperties();
        var context = new RagContextBuilder(documents, chunks, properties).build(KNOWLEDGE_BASE_ID,
                "What is the final deadline?", List.of(hit(1_001L, 101L)));
        assertThat(context.evidence()).singleElement().satisfies(evidence -> assertThat(evidence.contextText()).isEqualTo(content));
        var estimator = new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator();
        assertThat(estimator.estimate(RagPromptTemplates.systemInstruction()) + estimator.estimate(context.userPrompt()))
                .isLessThanOrEqualTo(properties.inputTokenBudget());
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
        assertThat(context.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
            assertThat(evidence.citation().chunkId()).isEqualTo(1_001L);
            assertThat(evidence.contextText()).hasSizeLessThan(content.length());
            assertThat(context.userPrompt()).contains(evidence.contextText());
            assertThat(evidence.sourceContentSha256()).matches("[a-f0-9]{64}");
        });
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
        ReflectionTestUtils.setField(result, "status", com.wxx.aidocumentagent.document.domain.DocumentStatus.READY);
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
