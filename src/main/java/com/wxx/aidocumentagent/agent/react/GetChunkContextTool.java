package com.wxx.aidocumentagent.agent.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** 只能扩展本轮检索已授权的 anchor chunk，邻片查询同时带知识库和文档边界。 */
public final class GetChunkContextTool implements AgentTool<GetChunkContextTool.Input, EvidenceResult> {

    private final DocumentChunkRepository chunks;
    private final DocumentRepository documents;
    private final int maxChunkCharacters;

    public GetChunkContextTool(DocumentChunkRepository chunks, DocumentRepository documents) {
        this(chunks, documents, 4_500);
    }

    public GetChunkContextTool(DocumentChunkRepository chunks, DocumentRepository documents, int maxChunkCharacters) {
        this.chunks = Objects.requireNonNull(chunks);
        this.documents = Objects.requireNonNull(documents);
        if (maxChunkCharacters < 64 || maxChunkCharacters > 8_000) throw new IllegalArgumentException("Invalid context limit");
        this.maxChunkCharacters = maxChunkCharacters;
    }

    public record Input(@Positive long chunkId, @Min(1) @Max(2) Integer radius) { }

    @Override public String name() { return "getChunkContext"; }
    @Override public boolean readOnly() { return true; }
    @Override public String description() { return "Read adjacent passages around a previously found chunk."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public Map<String, String> summarize(Input input) {
        return Map.of("chunkId", Long.toString(input.chunkId()),
                "radius", Integer.toString(input.radius() == null ? 1 : input.radius()));
    }

    @Override
    public EvidenceResult execute(Input input, ToolExecutionContext context) {
        context.checkActive();
        if (!context.isAuthorizedChunk(input.chunkId())) return new EvidenceResult(List.of());
        DocumentChunk anchor = chunks.findByIdAndKnowledgeBaseId(input.chunkId(), context.knowledgeBaseId())
                .filter(chunk -> chunk.getKnowledgeBaseId() == context.knowledgeBaseId()).orElse(null);
        if (anchor == null) return new EvidenceResult(List.of());
        Document document = documents.findByIdAndKnowledgeBaseId(anchor.getDocumentId(), context.knowledgeBaseId())
                .filter(found -> found.getKnowledgeBaseId() == context.knowledgeBaseId()
                        && found.getStatus() == DocumentStatus.READY).orElse(null);
        if (document == null) return new EvidenceResult(List.of());
        int radius = input.radius() == null ? 1 : input.radius();
        List<DocumentChunk> neighbors = chunks.findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(
                context.knowledgeBaseId(), document.getId(), Math.max(0, anchor.getChunkIndex() - radius),
                anchor.getChunkIndex() + radius);
        List<EvidenceChunk> evidence = new ArrayList<>();
        for (DocumentChunk chunk : neighbors) {
            if (chunk.getKnowledgeBaseId() == context.knowledgeBaseId()
                    && chunk.getDocumentId().equals(document.getId()) && chunk.getContent() != null
                    && !chunk.getContent().isBlank()) {
                evidence.add(new EvidenceChunk(context.knowledgeBaseId(), chunk.getId(), document.getId(),
                        document.getOriginalName(), chunk.getPageFrom(), chunk.getPageTo(),
                        SearchKnowledgeBaseTool.excerpt(chunk.getContent(), maxChunkCharacters),
                        com.wxx.aidocumentagent.agent.ClaimEvidenceValidator.sha256(chunk.getContent())));
            }
        }
        return new EvidenceResult(evidence);
    }
}
