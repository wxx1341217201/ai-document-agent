package com.wxx.aidocumentagent.agent.react;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wxx.aidocumentagent.agent.AgentConversationContext;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.rag.application.RagRetrievalGateway;
import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReadOnlyToolsTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final ToolExecutionContext context = context();

    @Test
    void aComparisonGoalDoesNotForceItsFirstBookIntoTheOtherFocusedSearch() {
        context.setGoal("Compare Alice and Darcy");
        var first = new RetrievedChunk(10, 7, 22, "Alice", null, null, 1, .9, null, null, .03, List.of(RetrievalChannel.VECTOR));
        var second = new RetrievedChunk(11, 7, 21, "Darcy income", null, null, 2, .8, null, null, .02, List.of(RetrievalChannel.VECTOR));
        RagRetrievalGateway gateway = (kb, query, topK, rerank) -> new RetrievalResult(List.of(first, second), false, List.of());
        var book = mock(Document.class);
        when(book.getId()).thenReturn(21L);
        when(book.getKnowledgeBaseId()).thenReturn(7L);
        when(book.getStatus()).thenReturn(DocumentStatus.READY);
        when(book.getOriginalName()).thenReturn("pride-and-prejudice.txt");
        var other = mock(Document.class);
        when(other.getId()).thenReturn(22L);
        when(other.getKnowledgeBaseId()).thenReturn(7L);
        when(other.getStatus()).thenReturn(DocumentStatus.READY);
        when(other.getOriginalName()).thenReturn("alice.txt");
        var source = mock(DocumentChunk.class);
        when(source.getId()).thenReturn(11L);
        when(source.getKnowledgeBaseId()).thenReturn(7L);
        when(source.getDocumentId()).thenReturn(21L);
        when(source.getContent()).thenReturn("Darcy has an income");
        var otherSource = mock(DocumentChunk.class);
        when(otherSource.getId()).thenReturn(10L);
        when(otherSource.getKnowledgeBaseId()).thenReturn(7L);
        when(otherSource.getDocumentId()).thenReturn(22L);
        when(otherSource.getContent()).thenReturn("Alice story");
        when(documents.findByKnowledgeBaseIdAndIdIn(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(List.of(book, other));
        when(chunks.findByKnowledgeBaseIdAndIdIn(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(List.of(source, otherSource));
        assertThat(new SearchKnowledgeBaseTool(gateway, documents, chunks, new ReActProperties())
                .execute(new SearchKnowledgeBaseTool.Input("Darcy income", 2), context).chunks())
                .extracting(EvidenceChunk::documentId).contains(21L);
    }

    @Test
    void anInvalidTopIndexHitDoesNotConsumeTheFinalEvidenceSlot() {
        var properties = new com.wxx.aidocumentagent.rag.RagProperties();
        properties.setRetrievalCandidateTopK(20);
        var invalid = new RetrievedChunk(10, 7, 21, "topic", null, null, 1, .9, null, null, .03, List.of(RetrievalChannel.VECTOR));
        var valid = new RetrievedChunk(11, 7, 21, "topic", null, null, 2, .8, null, null, .02, List.of(RetrievalChannel.VECTOR));
        RagRetrievalGateway gateway = (kb, query, topK, rerank) -> {
            assertThat(topK).isEqualTo(20);
            return new RetrievalResult(List.of(invalid, valid), false, List.of());
        };
        var document = mock(Document.class);
        when(document.getId()).thenReturn(21L);
        when(document.getKnowledgeBaseId()).thenReturn(7L);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getOriginalName()).thenReturn("guide.txt");
        var source = mock(DocumentChunk.class);
        when(source.getId()).thenReturn(11L);
        when(source.getKnowledgeBaseId()).thenReturn(7L);
        when(source.getDocumentId()).thenReturn(21L);
        when(source.getContent()).thenReturn("Verified source text");
        when(documents.findByKnowledgeBaseIdAndIdIn(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(List.of(document));
        when(chunks.findByKnowledgeBaseIdAndIdIn(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(List.of(source));
        var search = new SearchKnowledgeBaseTool(new com.wxx.aidocumentagent.rag.application.RagQueryRetrievalService(gateway, properties, null),
                documents, chunks, new ReActProperties());
        assertThat(search.execute(new SearchKnowledgeBaseTool.Input("topic", 1), context).chunks())
                .singleElement().extracting(EvidenceChunk::chunkId).isEqualTo(11L);
    }

    @Test
    void 文档元数据查询始终带可信知识库标识() {
        GetDocumentMetadataTool tool = new GetDocumentMetadataTool(documents);
        when(documents.findByIdAndKnowledgeBaseId(21L, 7L)).thenReturn(Optional.empty());
        assertThat(tool.execute(new GetDocumentMetadataTool.Input(21), context).found()).isFalse();
        verify(documents).findByIdAndKnowledgeBaseId(21L, 7L);
    }

    @Test
    void 未授权chunk不会读取持久化数据() {
        GetChunkContextTool tool = new GetChunkContextTool(chunks, documents);
        assertThat(tool.execute(new GetChunkContextTool.Input(11, 1), context).chunks()).isEmpty();
        verifyNoInteractions(chunks, documents);
    }

    @Test
    void 索引跨库命中不会触发后续查询() {
        RagRetrievalGateway retrieval = mock(RagRetrievalGateway.class);
        RetrievedChunk foreign = new RetrievedChunk(11, 999, 21, "不可信索引内容", 1, 1,
                null, null, 1, 1D, 0.5, List.of(RetrievalChannel.KEYWORD));
        when(retrieval.retrieve(7L, "查询", 6, true))
                .thenReturn(new RetrievalResult(List.of(foreign), false, List.of()));
        SearchKnowledgeBaseTool tool = new SearchKnowledgeBaseTool(retrieval, documents, chunks,
                new ReActProperties());
        assertThat(tool.execute(new SearchKnowledgeBaseTool.Input("查询", null), context).chunks()).isEmpty();
        verifyNoInteractions(documents, chunks);
    }

    @Test
    void 当前库候选仍从数据库验证真实归属() {
        RagRetrievalGateway retrieval = mock(RagRetrievalGateway.class);
        RetrievedChunk hit = new RetrievedChunk(11, 7, 21, "不可信索引内容", 1, 1,
                null, null, 1, 1D, 0.5, List.of(RetrievalChannel.KEYWORD));
        when(retrieval.retrieve(7L, "查询", 6, true))
                .thenReturn(new RetrievalResult(List.of(hit), false, List.of()));
        DocumentChunk foreignChunk = mock(DocumentChunk.class);
        when(foreignChunk.getKnowledgeBaseId()).thenReturn(999L);
        when(chunks.findByKnowledgeBaseIdAndIdIn(7L, List.of(11L))).thenReturn(List.of(foreignChunk));
        Document foreignDocument = mock(Document.class);
        when(foreignDocument.getKnowledgeBaseId()).thenReturn(999L);
        when(documents.findByKnowledgeBaseIdAndIdIn(7L, List.of(21L))).thenReturn(List.of(foreignDocument));
        SearchKnowledgeBaseTool tool = new SearchKnowledgeBaseTool(retrieval, documents, chunks,
                new ReActProperties());
        assertThat(tool.execute(new SearchKnowledgeBaseTool.Input("查询", null), context).chunks()).isEmpty();
        verify(chunks).findByKnowledgeBaseIdAndIdIn(7L, List.of(11L));
        verify(documents).findByKnowledgeBaseIdAndIdIn(7L, List.of(21L));
    }

    @Test
    void lateFactComesFromPersistedSourceAndRerankFollowsConfiguration() {
        RagRetrievalGateway retrieval = mock(RagRetrievalGateway.class);
        RetrievedChunk hit = new RetrievedChunk(11, 7, 21, "untrusted index payload", 1, 1,
                null, null, 1, 1D, 0.5, List.of(RetrievalChannel.KEYWORD));
        var result = new RetrievalResult(List.of(hit), false, List.of());
        when(retrieval.retrieve(7L, "retry policy", 6, true)).thenReturn(result);
        when(retrieval.retrieve(7L, "retry policy", 6, false)).thenReturn(result);
        var source = mock(DocumentChunk.class);
        when(source.getId()).thenReturn(11L);
        when(source.getKnowledgeBaseId()).thenReturn(7L);
        when(source.getDocumentId()).thenReturn(21L);
        String content = "source prefix ".repeat(110) + "The service retries five times.";
        when(source.getContent()).thenReturn(content);
        var document = mock(Document.class);
        when(document.getId()).thenReturn(21L);
        when(document.getKnowledgeBaseId()).thenReturn(7L);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getOriginalName()).thenReturn("guide");
        when(chunks.findByKnowledgeBaseIdAndIdIn(7L, List.of(11L))).thenReturn(List.of(source));
        when(documents.findByKnowledgeBaseIdAndIdIn(7L, List.of(21L))).thenReturn(List.of(document));
        var properties = new ReActProperties();
        var tool = new SearchKnowledgeBaseTool(retrieval, documents, chunks, properties);
        var evidence = tool.execute(new SearchKnowledgeBaseTool.Input("retry policy", null), context);
        assertThat(evidence.chunks().getFirst().text()).isEqualTo(content).contains("retries five times")
                .doesNotContain("untrusted index payload");
        assertThat(evidence.retrievalSummary()).containsEntry("queryCount", "1");
        properties.setRerank(false);
        tool.execute(new SearchKnowledgeBaseTool.Input("retry policy", null), context);
        verify(retrieval).retrieve(7L, "retry policy", 6, true);
        verify(retrieval).retrieve(7L, "retry policy", 6, false);
    }

    @Test
    void authorizedContextExpandsAnchorPastOldPrefixAndStillChecksDocumentScope() {
        String content = "verified prefix ".repeat(100) + "The timeout is thirty seconds.";
        context.registerEvidence(List.of(new EvidenceChunk(7, 11, 21, "guide", 1, 1, content.substring(0, 1_200))));
        var source = mock(DocumentChunk.class);
        when(source.getId()).thenReturn(11L);
        when(source.getKnowledgeBaseId()).thenReturn(7L);
        when(source.getDocumentId()).thenReturn(21L);
        when(source.getChunkIndex()).thenReturn(0);
        when(source.getPageFrom()).thenReturn(1);
        when(source.getPageTo()).thenReturn(1);
        when(source.getContent()).thenReturn(content);
        var document = mock(Document.class);
        when(document.getId()).thenReturn(21L);
        when(document.getKnowledgeBaseId()).thenReturn(7L);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getOriginalName()).thenReturn("guide");
        when(chunks.findByIdAndKnowledgeBaseId(11L, 7L)).thenReturn(Optional.of(source));
        when(documents.findByIdAndKnowledgeBaseId(21L, 7L)).thenReturn(Optional.of(document));
        when(chunks.findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(7L, 21L, 0, 1))
                .thenReturn(List.of(source));
        var evidence = new GetChunkContextTool(chunks, documents).execute(new GetChunkContextTool.Input(11, 1), context);
        assertThat(evidence.chunks()).singleElement().extracting(EvidenceChunk::text).isEqualTo(content);
        context.registerEvidence(evidence.chunks());
        assertThat(context.evidence().getFirst().text()).contains("thirty seconds");
        assertThat(context.citations().getFirst().citationId()).isEqualTo("C1");
    }

    private ToolExecutionContext context() {
        AgentRequest request = new AgentRequest("问题", 7L, null, AgentMode.REACT, Map.of());
        AgentExecutionContext execution = AgentExecutionContext.forRequest(request, "trace", Instant.now().plusSeconds(5),
                AgentConversationContext.emptyFor(request), event -> { }, () -> false);
        return new ToolExecutionContext(7L, execution, Instant.now().plusSeconds(5), 20);
    }
}
