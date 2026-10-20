package com.wxx.aidocumentagent.agent.react;

import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import jakarta.validation.constraints.Positive;

public final class GetDocumentMetadataTool implements AgentTool<GetDocumentMetadataTool.Input, GetDocumentMetadataTool.Output> {

    private final DocumentRepository documents;

    public GetDocumentMetadataTool(DocumentRepository documents) {
        this.documents = Objects.requireNonNull(documents);
    }

    public record Input(@Positive long documentId) { }
    public record Output(boolean found, Long documentId, String name, String contentType, Long sizeBytes,
                         String status) { }

    @Override public String name() { return "getDocumentMetadata"; }
    @Override public boolean readOnly() { return true; }
    @Override public String description() { return "Read metadata for a document in the current knowledge base."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public Map<String, String> summarize(Input input) {
        return Map.of("documentId", Long.toString(input.documentId()));
    }

    @Override
    public Output execute(Input input, ToolExecutionContext context) {
        context.checkActive();
        Document document = documents.findByIdAndKnowledgeBaseId(input.documentId(), context.knowledgeBaseId())
                .filter(found -> found.getKnowledgeBaseId() == context.knowledgeBaseId()
                        && found.getStatus() != DocumentStatus.DELETED).orElse(null);
        if (document == null) return new Output(false, null, null, null, null, null);
        return new Output(true, document.getId(), document.getOriginalName(), document.getContentType(),
                document.getSizeBytes(), document.getStatus().name());
    }
}
