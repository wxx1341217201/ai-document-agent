package com.wxx.aidocumentagent.document.application;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.api.dto.DocumentChunkPageResponse;
import com.wxx.aidocumentagent.document.api.dto.DocumentChunkResponse;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.storage.DocumentStorage;
import com.wxx.aidocumentagent.document.storage.DocumentStorageException;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只读既有原文件和已持久化片段；不触发解析、索引或模型调用。 */
@Service
public class DocumentContentService {
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final DocumentStorage storage;

    public DocumentContentService(KnowledgeBaseRepository knowledgeBases, DocumentRepository documents,
                                  DocumentChunkRepository chunks, DocumentStorage storage) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.chunks = chunks;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public DocumentChunkPageResponse chunks(long knowledgeBaseId, long documentId, int page, int size) {
        requireKnowledgeBase(knowledgeBaseId);
        if (!documents.existsByIdAndKnowledgeBaseId(documentId, knowledgeBaseId)) {
            throw new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND);
        }
        var result = chunks.findAllProjectedByKnowledgeBaseIdAndDocumentId(knowledgeBaseId, documentId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "chunkIndex")));
        return new DocumentChunkPageResponse(result.getContent().stream().map(chunk -> new DocumentChunkResponse(
                chunk.getId(), chunk.getChunkIndex(), chunk.getContent(), chunk.getPageFrom(), chunk.getPageTo(),
                chunk.getSectionTitle())).toList(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Download download(long knowledgeBaseId, long documentId) {
        requireKnowledgeBase(knowledgeBaseId);
        var document = documents.findByIdAndKnowledgeBaseId(documentId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        try {
            return new Download(document.getOriginalName(), document.getExtension(), document.getSizeBytes(),
                    storage.load(document.getStorageKey()));
        }
        catch (DocumentStorageException exception) {
            var code = exception.getCause() instanceof NoSuchFileException
                    || exception.getCause() instanceof FileNotFoundException
                    ? DocumentErrorCode.FILE_NOT_FOUND : DocumentErrorCode.READ_FAILURE;
            throw new BusinessException(code, code.defaultMessage(), exception);
        }
    }

    private void requireKnowledgeBase(long knowledgeBaseId) {
        if (!knowledgeBases.existsById(knowledgeBaseId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    /** 流由 HTTP Resource 转换器消费并关闭，传输期间不持有数据库事务。 */
    public record Download(String originalName, String extension, long sizeBytes, InputStream content) { }
}
