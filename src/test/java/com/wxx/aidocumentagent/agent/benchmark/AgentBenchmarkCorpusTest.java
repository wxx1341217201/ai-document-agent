package com.wxx.aidocumentagent.agent.benchmark;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.chunking.TextChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import static com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentBenchmarkCorpusTest {
    private KnowledgeBaseRepository knowledgeBases;
    private DocumentRepository documents;
    private DocumentChunkRepository chunks;
    private DocumentChunk chunk;
    private AgentBenchmarkCorpus corpus;
    private AgentBenchmarkDataset dataset;
    @BeforeEach void setup() {
        knowledgeBases = mock(KnowledgeBaseRepository.class); documents = mock(DocumentRepository.class); chunks = mock(DocumentChunkRepository.class);
        when(knowledgeBases.existsById(2L)).thenReturn(true);
        Document doc = Document.uploaded(2L, "unit-fixture.txt", "unit-storage", "text/plain", "txt", 20L, "a".repeat(64));
        ReflectionTestUtils.setField(doc, "id", 20L); ReflectionTestUtils.setField(doc, "status", DocumentStatus.READY);
        chunk = DocumentChunk.create(2, 20, new TextChunk(0, TEXT, 5, null, null, null, Map.of()));
        ReflectionTestUtils.setField(chunk, "id", 250L);
        when(documents.findByKnowledgeBaseId(eq(2L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(doc)));
        when(chunks.findByKnowledgeBaseIdAndDocumentIdOrderByChunkIndexAsc(2, 20)).thenReturn(List.of(chunk));
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(2L), anyCollection())).thenReturn(List.of(chunk));
        corpus = new AgentBenchmarkCorpus(knowledgeBases, documents, chunks);
        dataset = new AgentBenchmarkDataset(List.of(task(AgentMode.REACT)), 2);
    }
    @Test void scopedSourceAndActualPrefixAreVerified() {
        var baseline = corpus.snapshot(dataset);
        var evidence = corpus.verify(2, baseline, List.of(new AgentBenchmarkTrace.Evidence(CITATION, TEXT)));
        assertThat(evidence).containsExactly(EVIDENCE);
        verify(documents).findByKnowledgeBaseId(eq(2L), any(Pageable.class));
        verify(chunks).findByKnowledgeBaseIdAndIdIn(2L, List.of(250L));
        verify(documents, never()).findById(any()); verify(chunks, never()).findById(any());
    }
    @Test void rejectsForeignEvidenceOrFabricatedTextAndDetectsDrift() {
        var baseline = corpus.snapshot(dataset);
        var foreign = new AgentCitation(99, "C1", 20, "unit-fixture.txt", 250, null, null, TEXT);
        assertThatThrownBy(() -> corpus.verify(2, baseline, List.of(new AgentBenchmarkTrace.Evidence(foreign, TEXT)))).hasMessage("UNVERIFIED_CURRENT_CONTEXT");
        assertThatThrownBy(() -> corpus.verify(2, baseline, List.of(new AgentBenchmarkTrace.Evidence(CITATION, "fabricated source")))).hasMessage("UNVERIFIED_CURRENT_CONTEXT");
        ReflectionTestUtils.setField(chunk, "content", "changed source");
        assertThat(corpus.stable(2, baseline)).isFalse();
        assertThatThrownBy(() -> corpus.verify(2, baseline, List.of(new AgentBenchmarkTrace.Evidence(CITATION, TEXT)))).hasMessage("UNVERIFIED_CURRENT_CONTEXT");
    }
    @Test void missingLabeledChunkStopsBeforeAnyModelInvocation() {
        when(chunks.findByKnowledgeBaseIdAndDocumentIdOrderByChunkIndexAsc(2, 20)).thenReturn(List.of());
        assertThatThrownBy(() -> corpus.snapshot(dataset)).hasMessage("MISSING_OR_FOREIGN_LABELED_CHUNK");
    }
    @Test void offlineEvidenceMustMatchFrozenTextHashAndDocument() {
        var baseline = corpus.snapshot(dataset);
        AgentBenchmarkCorpus.validateLabels(dataset, baseline);
        AgentBenchmarkCorpus.verifyFrozen(2, baseline, List.of(EVIDENCE));
        var edited = new com.wxx.aidocumentagent.rag.application.RagEvidenceChunk(2, EVIDENCE.citation(), "invented text", EVIDENCE.sourceContentSha256());
        assertThatThrownBy(() -> AgentBenchmarkCorpus.verifyFrozen(2, baseline, List.of(edited)))
                .hasMessage("OBSERVATION_NOT_IN_FROZEN_CORPUS");
        var wrongHash = new com.wxx.aidocumentagent.rag.application.RagEvidenceChunk(2, EVIDENCE.citation(), TEXT, "b".repeat(64));
        assertThatThrownBy(() -> AgentBenchmarkCorpus.verifyFrozen(2, baseline, List.of(wrongHash)))
                .hasMessage("OBSERVATION_NOT_IN_FROZEN_CORPUS");
    }
}
