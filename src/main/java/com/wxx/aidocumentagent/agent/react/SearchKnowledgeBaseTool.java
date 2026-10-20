package com.wxx.aidocumentagent.agent.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.rag.application.RagRetrievalGateway;
import com.wxx.aidocumentagent.rag.application.RagQueryRetrievalService;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.application.EvidenceRelevance;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 索引只提供候选 ID，返回前再从当前知识库的文档和 chunk 表复核。 */
public final class SearchKnowledgeBaseTool implements AgentTool<SearchKnowledgeBaseTool.Input, EvidenceResult> {

    private final RagQueryRetrievalService retrieval;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final ReActProperties properties;

    public SearchKnowledgeBaseTool(RagRetrievalGateway retrieval, DocumentRepository documents,
                                   DocumentChunkRepository chunks, ReActProperties properties) {
        this(new RagQueryRetrievalService(retrieval, new RagProperties(), null), documents, chunks, properties);
    }

    public SearchKnowledgeBaseTool(RagQueryRetrievalService retrieval, DocumentRepository documents,
                                   DocumentChunkRepository chunks, ReActProperties properties) {
        this.retrieval = Objects.requireNonNull(retrieval);
        this.documents = Objects.requireNonNull(documents);
        this.chunks = Objects.requireNonNull(chunks);
        this.properties = Objects.requireNonNull(properties);
    }

    public record Input(@NotBlank @Size(max = 2_000) String query, @Min(1) @Max(20) Integer topK) {
    }

    @Override public String name() { return "searchKnowledgeBase"; }
    @Override public boolean readOnly() { return true; }
    @Override public String description() { return "Search the current knowledge base using a short factual query. Prefer topK=3; returned source passages can be cited directly."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public Map<String, String> summarize(Input input) {
        return Map.of("queryLength", Integer.toString(input.query().length()),
                "topK", Integer.toString(input.topK() == null ? properties.getTopK() : input.topK()),
                "rerankRequested", Boolean.toString(properties.isRerank()));
    }

    @Override
    public EvidenceResult execute(Input input, ToolExecutionContext context) {
        context.checkActive();
        int topK = input.topK() == null ? properties.getTopK() : input.topK();
        var outcome = retrieval.retrieve(context.knowledgeBaseId(), input.query().strip(), context.history(), topK,
                properties.isRerank());
        context.diagnostic("retrieval", Map.of("query", input.query(), "trace", outcome.trace(),
                "selectedChunkIds", outcome.retrieval().chunks().stream().map(RetrievedChunk::chunkId).toList()));
        Map<String, String> retrievalSummary = Map.of("queryCount", Integer.toString(outcome.trace().queries().size()),
                "rerankApplied", Boolean.toString(outcome.retrieval().rerankingApplied()),
                "retrievalDegraded", Boolean.toString(outcome.degraded()));
        List<RetrievedChunk> hits = outcome.candidates().stream()
                .filter(hit -> hit.knowledgeBaseId() == context.knowledgeBaseId()).toList();
        if (hits.isEmpty()) return new EvidenceResult(List.of(), retrievalSummary);
        Map<Long, DocumentChunk> scopedChunks = chunks.findByKnowledgeBaseIdAndIdIn(context.knowledgeBaseId(),
                hits.stream().map(RetrievedChunk::chunkId).toList()).stream()
                .filter(chunk -> chunk.getKnowledgeBaseId() == context.knowledgeBaseId())
                .collect(Collectors.toMap(DocumentChunk::getId, Function.identity(), (first, ignored) -> first));
        Map<Long, Document> scopedDocuments = documents.findByKnowledgeBaseIdAndIdIn(context.knowledgeBaseId(),
                hits.stream().map(RetrievedChunk::documentId).toList()).stream()
                .filter(document -> document.getKnowledgeBaseId() == context.knowledgeBaseId()
                        && document.getStatus() == DocumentStatus.READY)
                .collect(Collectors.toMap(Document::getId, Function.identity(), (first, ignored) -> first));
        List<EvidenceChunk> evidence = new ArrayList<>();
        // A focused subquery must not inherit a different document name from a comparison's overall goal.
        String intent = input.query();
        if (EvidenceRelevance.metadata(intent) && scopedDocuments.values().stream()
                .noneMatch(document -> EvidenceRelevance.documentAffinity(input.query(), document.getOriginalName()) > 0))
            intent = context.goal() + " " + input.query();
        final String documentIntent = intent;
        boolean namedDocument = !EvidenceRelevance.comparison(documentIntent) && scopedDocuments.values().stream()
                .anyMatch(document -> EvidenceRelevance.documentAffinity(documentIntent, document.getOriginalName()) > 0);
        if (EvidenceRelevance.metadata(intent)) {
            scopedDocuments.values().stream().filter(document -> !namedDocument
                    || EvidenceRelevance.documentAffinity(documentIntent, document.getOriginalName()) > 0).limit(2).forEach(document -> {
                for (DocumentChunk header : chunks.findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(
                        context.knowledgeBaseId(), document.getId(), 0, 1)) {
                    if (header.getKnowledgeBaseId() == context.knowledgeBaseId() && header.getDocumentId().equals(document.getId())
                            && header.getContent() != null && !header.getContent().isBlank())
                        evidence.add(new EvidenceChunk(context.knowledgeBaseId(), header.getId(), document.getId(), document.getOriginalName(),
                                header.getPageFrom(), header.getPageTo(), excerpt(header.getContent(), properties.getMaxChunkCharacters()),
                                com.wxx.aidocumentagent.agent.ClaimEvidenceValidator.sha256(header.getContent())));
                }
            });
        }
        for (RetrievedChunk hit : hits) {
            DocumentChunk chunk = scopedChunks.get(hit.chunkId());
            Document document = scopedDocuments.get(hit.documentId());
            if (chunk != null && document != null && (!namedDocument
                    || EvidenceRelevance.documentAffinity(documentIntent, document.getOriginalName()) > 0)
                    && chunk.getDocumentId().equals(document.getId())
                    && chunk.getContent() != null && !chunk.getContent().isBlank()) {
                evidence.add(new EvidenceChunk(context.knowledgeBaseId(), chunk.getId(), document.getId(),
                        document.getOriginalName(), chunk.getPageFrom(), chunk.getPageTo(),
                        excerpt(chunk.getContent(), properties.getMaxChunkCharacters()),
                        com.wxx.aidocumentagent.agent.ClaimEvidenceValidator.sha256(chunk.getContent())));
                if (evidence.size() >= topK) break;
            }
        }
        context.diagnostic("source_filter", Map.of("hitChunkIds", hits.stream().map(RetrievedChunk::chunkId).toList(),
                "acceptedChunkIds", evidence.stream().map(EvidenceChunk::chunkId).toList(), "checks", "KB_DOCUMENT_READY_SOURCE_AND_INTENT"));
        return new EvidenceResult(evidence, retrievalSummary, input.query());
    }

    static String excerpt(String content) {
        return excerpt(content, 4_500);
    }

    static String excerpt(String content, int maximum) {
        int count = content.codePointCount(0, content.length());
        return count <= maximum ? content : content.substring(0, content.offsetByCodePoints(0, maximum));
    }
}
