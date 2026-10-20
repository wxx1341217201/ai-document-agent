package com.wxx.aidocumentagent.agent.benchmark;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** 整个 READY 语料集固定指纹，返回前再次复核实际上下文；所有查询都携带 KB 边界。 */
@Component
@ConditionalOnProperty(prefix = "app.benchmark.agent", name = "enabled", havingValue = "true")
final class AgentBenchmarkCorpus {
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    AgentBenchmarkCorpus(KnowledgeBaseRepository knowledgeBases, DocumentRepository documents, DocumentChunkRepository chunks) {
        this.knowledgeBases = knowledgeBases; this.documents = documents; this.chunks = chunks;
    }

    Baseline snapshot(AgentBenchmarkDataset dataset) {
        Baseline baseline = load(dataset.knowledgeBaseId());
        validateLabels(dataset, baseline);
        return baseline;
    }

    static void validateLabels(AgentBenchmarkDataset dataset, Baseline baseline) {
        for (var source : baseline.sources().values()) {
            if (!AgentBenchmarkText.sha256(source.content()).equals(source.contentSha256())) {
                throw new IllegalArgumentException("CORPUS_SOURCE_HASH_MISMATCH");
            }
        }
        for (var task : dataset.tasks()) {
            if (!baseline.sources().keySet().containsAll(task.acceptableChunkIds())) throw new IllegalArgumentException("MISSING_OR_FOREIGN_LABELED_CHUNK");
            for (var fact : task.requiredFacts()) {
                if (fact.supportingChunkIds().stream().noneMatch(id -> AgentBenchmarkText.normalize(baseline.sources().get(id).content())
                        .contains(AgentBenchmarkText.normalize(fact.sourceQuote())))) throw new IllegalArgumentException("SOURCE_QUOTE_NOT_IN_LABELED_CHUNK");
            }
            if (task.tags().contains("CROSS_DOCUMENT") && task.acceptableChunkIds().stream()
                    .map(id -> baseline.sources().get(id).documentId()).distinct().count() < 2) throw new IllegalArgumentException("COMPARISON_REQUIRES_DISTINCT_DOCUMENTS");
        }
    }

    /** Offline verification uses the frozen full text, never the answer or a reviewer-supplied quote. */
    static void verifyFrozen(long kb, Baseline baseline, List<RagEvidenceChunk> evidence) {
        for (var item : evidence) {
            var citation = item.citation();
            var source = baseline.sources().get(citation.chunkId());
            if (item.knowledgeBaseId() != kb || source == null || source.documentId() != citation.documentId()
                    || !source.documentName().equals(citation.documentName())
                    || !Objects.equals(source.pageFrom(), citation.pageFrom()) || !Objects.equals(source.pageTo(), citation.pageTo())
                    || !source.contentSha256().equals(item.sourceContentSha256())
                    || item.contextText().isBlank() || !source.content().startsWith(item.contextText())
                    || citation.quote().isBlank() || !source.content().startsWith(citation.quote())) {
                throw new IllegalArgumentException("OBSERVATION_NOT_IN_FROZEN_CORPUS");
            }
        }
    }

    boolean stable(long kb, Baseline baseline) { return baseline.fingerprint().equals(load(kb).fingerprint()); }

    List<RagEvidenceChunk> verify(long kb, Baseline baseline, List<AgentBenchmarkTrace.Evidence> snapshots) {
        if (snapshots.isEmpty()) return List.of();
        List<Long> ids = snapshots.stream().map(e -> e.citation().chunkId()).distinct().toList();
        Map<Long, DocumentChunk> persisted = new HashMap<>();
        for (var chunk : chunks.findByKnowledgeBaseIdAndIdIn(kb, ids)) {
            if (chunk.getKnowledgeBaseId() == null || chunk.getKnowledgeBaseId() != kb || !ids.contains(chunk.getId())) throw new IllegalArgumentException("FOREIGN_PERSISTED_CONTEXT");
            persisted.put(chunk.getId(), chunk);
        }
        Map<String, RagEvidenceChunk> accepted = new LinkedHashMap<>();
        for (var snapshot : snapshots) {
            var c = snapshot.citation();
            Source original = baseline.sources().get(c.chunkId());
            DocumentChunk current = persisted.get(c.chunkId());
            if (c.knowledgeBaseId() != kb || original == null || current == null || current.getContent() == null
                    || !AgentBenchmarkText.sha256(current.getContent()).equals(original.contentSha256())
                    || current.getDocumentId() != c.documentId() || original.documentId() != c.documentId()
                    || !original.documentName().equals(c.documentName()) || snapshot.contextText() == null || snapshot.contextText().isBlank()
                    || !current.getContent().startsWith(snapshot.contextText()) || c.quote().isBlank()
                    || !current.getContent().startsWith(c.quote()) || !Objects.equals(current.getPageFrom(), c.pageFrom())
                    || !Objects.equals(current.getPageTo(), c.pageTo()) || !Objects.equals(original.pageFrom(), c.pageFrom())
                    || !Objects.equals(original.pageTo(), c.pageTo())) throw new IllegalArgumentException("UNVERIFIED_CURRENT_CONTEXT");
            var evidence = new RagEvidenceChunk(kb, AgentBenchmarkEvaluator.toRag(c), snapshot.contextText(), original.contentSha256());
            var prior = accepted.get(c.citationId());
            if (prior != null && (!prior.citation().equals(evidence.citation())
                    || !(prior.contextText().startsWith(evidence.contextText()) || evidence.contextText().startsWith(prior.contextText())))) {
                throw new IllegalArgumentException("CONTEXT_CITATION_CHANGED_IN_TURN");
            }
            if (prior == null || evidence.contextText().length() > prior.contextText().length()) accepted.put(c.citationId(), evidence);
        }
        return List.copyOf(accepted.values());
    }

    private Baseline load(long kb) {
        if (kb <= 0 || !knowledgeBases.existsById(kb)) throw new IllegalArgumentException("BENCHMARK_KNOWLEDGE_BASE_NOT_FOUND");
        Map<Long, Source> sources = new HashMap<>();
        StringBuilder metadata = new StringBuilder();
        var scoped = documents.findByKnowledgeBaseId(kb, Pageable.unpaged()).getContent().stream()
                .sorted(java.util.Comparator.comparing(d -> d.getId())).toList();
        for (var doc : scoped) {
            if (doc.getKnowledgeBaseId() != kb || doc.getId() == null) throw new IllegalArgumentException("FOREIGN_PERSISTED_DOCUMENT");
            if (doc.getStatus() != DocumentStatus.READY) continue;
            metadata.append(doc.getId()).append('|').append(doc.getOriginalName()).append('|').append(doc.getContentType())
                    .append('|').append(doc.getSizeBytes()).append('|').append(doc.getStatus()).append('\n');
            for (var chunk : chunks.findByKnowledgeBaseIdAndDocumentIdOrderByChunkIndexAsc(kb, doc.getId())) {
                if (chunk.getKnowledgeBaseId() == null || chunk.getKnowledgeBaseId() != kb || chunk.getId() == null
                        || !doc.getId().equals(chunk.getDocumentId()) || chunk.getContent() == null || chunk.getContent().isBlank()) throw new IllegalArgumentException("FOREIGN_OR_INVALID_PERSISTED_CHUNK");
                Source source = new Source(doc.getId(), doc.getOriginalName(), chunk.getContent(),
                        AgentBenchmarkText.sha256(chunk.getContent()), chunk.getPageFrom(), chunk.getPageTo());
                if (sources.putIfAbsent(chunk.getId(), source) != null) throw new IllegalArgumentException("DUPLICATE_PERSISTED_CHUNK");
            }
        }
        sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> metadata.append(e.getKey()).append('|')
                .append(e.getValue().documentId()).append('|').append(e.getValue().contentSha256()).append('|')
                .append(e.getValue().pageFrom()).append('|').append(e.getValue().pageTo()).append('\n'));
        return new Baseline(Map.copyOf(sources), AgentBenchmarkText.sha256(metadata.toString()));
    }
    record Source(long documentId, String documentName, String content, String contentSha256, Integer pageFrom, Integer pageTo) { }
    record Baseline(Map<Long, Source> sources, String fingerprint) { }
}
