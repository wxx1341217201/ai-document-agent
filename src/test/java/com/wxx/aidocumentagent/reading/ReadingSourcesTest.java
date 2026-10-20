package com.wxx.aidocumentagent.reading;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import com.wxx.aidocumentagent.agent.ClaimEvidenceValidator;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import com.wxx.aidocumentagent.retrieval.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadingSourcesTest {
    private final DocumentRepository docs = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final ReadingSources sources = new ReadingSources(docs, chunks, retriever);
    private List<DocumentRef> scope;

    @BeforeEach void readySource() {
        var doc = mock(Document.class);
        when(doc.getId()).thenReturn(15L); when(doc.getOriginalName()).thenReturn("受控资料.txt");
        when(doc.getSha256()).thenReturn("a".repeat(64)); when(doc.getStatus()).thenReturn(DocumentStatus.READY);
        when(docs.findByKnowledgeBaseIdAndIdIn(8, Set.of(15L))).thenReturn(List.of(doc));
        version("hash-v1"); scope = sources.snapshot(8, Set.of(15L));
    }

    @Test void submissionDoesNotScanOrLoadChunkContents() {
        clearInvocations(chunks);
        assertThat(sources.select(8, Set.of(15L))).singleElement().satisfies(d -> assertThat(d.chunks()).isZero());
        verifyNoInteractions(chunks);
        assertThatThrownBy(() -> sources.select(8, Set.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sources.select(8, Set.of(999L))).isInstanceOf(BusinessException.class);
    }

    @Test void searchPassesScopeAndRejectsStaleOrForeignIndexHits() {
        var hit = new RetrievedChunk(100, 8, 15, "原文", null, null, 1, .9, null, null, .1, List.of(RetrievalChannel.VECTOR));
        var foreign = new RetrievedChunk(101, 8, 99, "别的文档", null, null, 2, .8, null, null, .05, List.of(RetrievalChannel.VECTOR));
        when(retriever.retrieve(any(), eq(false))).thenReturn(new RetrievalResult(List.of(hit, foreign), false, List.of()));
        var own = row(100, 15, "原文"); var excluded = row(101, 99, "别的文档");
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(8L), anyCollection())).thenReturn(List.of(own, excluded));
        assertThat(sources.search(8, scope, "原文")).extracting(Source::chunkId).containsExactly(100L);
        var query = ArgumentCaptor.forClass(RetrievalQuery.class); verify(retriever).retrieve(query.capture(), eq(false));
        assertThat(query.getValue().documentIds()).containsExactly(15L);
        when(own.getContent()).thenReturn("重新解析后的原文");
        assertThat(sources.search(8, scope, "原文")).isEmpty();
    }

    @Test void detectsSameFileHashButChangedChunkBeforeRetrieval() {
        version("changed-chunk");
        assertThatThrownBy(() -> sources.search(8, scope, "原文"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ReadingError.SOURCE_CHANGED));
        verifyNoInteractions(retriever);
    }
    @Test void externalRerankingRequiresAnExplicitReadingConfiguration() {
        when(retriever.retrieve(any(), eq(true))).thenReturn(new RetrievalResult(List.of(), false, List.of()));
        var optedIn = new ReadingSources(docs, chunks, retriever, true);
        assertThat(optedIn.search(8, scope, "范围内检索")).isEmpty();
        verify(retriever).retrieve(any(), eq(true));
    }

    @Test void contextMustStartFromPreviouslyAuthorizedChunk() {
        assertThatThrownBy(() -> sources.context(8, scope, new ReadingState(), 999)).isInstanceOf(BusinessException.class);
        verify(chunks, never()).findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test void largeChunkReadsResumeWithoutSkippingTextOrSplittingSurrogatePairs() {
        String text = "甲".repeat(4199) + "\uD83D\uDE80" + "乙".repeat(100);
        var projection = mock(DocumentChunkRepository.ContentSummary.class);
        when(projection.getId()).thenReturn(100L); when(projection.getChunkIndex()).thenReturn(0);
        when(projection.getContent()).thenReturn(text); when(projection.getContentHash()).thenReturn(ClaimEvidenceValidator.sha256(text));
        when(chunks.findAllProjectedByKnowledgeBaseIdAndDocumentIdAndChunkIndexGreaterThanEqual(eq(8L), eq(15L), eq(0), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(projection)));
        var first = sources.read(8, scope, 15, Cursor.start());
        assertThat(first.next()).isEqualTo(new Cursor(0, 4199, 0));
        var second = sources.read(8, scope, 15, first.next());
        assertThat(first.sources().getFirst().text() + second.sources().getFirst().text()).isEqualTo(text);
        assertThat(second.next()).isEqualTo(new Cursor(1, 0, 1));
    }

    @Test void finalVerificationChecksStoredExcerptNotOnlyItsClaimedHash() {
        var state = new ReadingState(); var doc = scope.getFirst();
        state.sources.add(new Source("C1", 15, doc.name(), doc.fingerprint(), 100, 0, "trusted-hash", 0, "捏造原文", null, null, null));
        var persisted = row(100, 15, "真实原文"); when(persisted.getContentHash()).thenReturn("trusted-hash");
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(8L), anyCollection())).thenReturn(List.of(persisted));
        assertThatThrownBy(() -> sources.verify(8, scope, state)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ReadingError.SOURCE_CHANGED));
    }

    private void version(String hash) {
        var row = mock(DocumentChunkRepository.VersionSummary.class);
        when(row.getId()).thenReturn(100L); when(row.getChunkIndex()).thenReturn(0); when(row.getContentHash()).thenReturn(hash);
        when(row.getUpdatedAt()).thenReturn(LocalDateTime.parse("2026-10-20T00:00:00"));
        when(chunks.findVersionsByKnowledgeBaseIdAndDocumentId(eq(8L), eq(15L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
    }
    private DocumentChunk row(long id, long document, String text) {
        var row = mock(DocumentChunk.class); when(row.getId()).thenReturn(id); when(row.getDocumentId()).thenReturn(document);
        when(row.getContent()).thenReturn(text); when(row.getContentHash()).thenReturn(ClaimEvidenceValidator.sha256(text)); return row;
    }
}
