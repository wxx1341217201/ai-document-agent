package com.wxx.aidocumentagent.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.api.dto.DocumentPageResponse;
import com.wxx.aidocumentagent.document.api.dto.DocumentResponse;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.storage.DocumentStorage;
import com.wxx.aidocumentagent.document.storage.DocumentStorageException;
import com.wxx.aidocumentagent.document.storage.StoredObject;
import com.wxx.aidocumentagent.ingestion.application.DocumentIngestionRequestService;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档元数据和原始文件生命周期操作的应用服务。
 */
@Service
public class DocumentApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentApplicationService.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentStorage documentStorage;
    private final DocumentUploadValidator documentUploadValidator;
    private final DocumentIngestionRequestService documentIngestionRequestService;

    @Autowired
    public DocumentApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                      DocumentRepository documentRepository,
                                      DocumentStorage documentStorage,
                                      DocumentUploadValidator documentUploadValidator,
                                      DocumentIngestionRequestService documentIngestionRequestService) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentStorage = documentStorage;
        this.documentUploadValidator = documentUploadValidator;
        this.documentIngestionRequestService = documentIngestionRequestService;
    }

    /** 保持已有单元测试和非 Spring 适配器的构造方式；生产环境始终注入摄取请求服务。 */
    public DocumentApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                      DocumentRepository documentRepository,
                                      DocumentStorage documentStorage,
                                      DocumentUploadValidator documentUploadValidator) {
        this(knowledgeBaseRepository, documentRepository, documentStorage, documentUploadValidator, null);
    }

    /**
     * 同一知识库中的相同内容会以 DUPLICATE_DOCUMENT 拒绝，不会静默复用已有文档。
     */
    @Transactional
    public DocumentResponse upload(long knowledgeBaseId, UploadDocumentCommand command) {
        requireKnowledgeBase(knowledgeBaseId);
        DocumentUploadValidator.ValidatedDocument validatedDocument = documentUploadValidator.validate(command);
        String sha256 = calculateSha256(command);
        if (documentRepository.findByKnowledgeBaseIdAndSha256(knowledgeBaseId, sha256).isPresent()) {
            throw duplicateDocument();
        }

        StoredObject storedObject = store(command, validatedDocument.extension());
        Document document = Document.uploaded(knowledgeBaseId, validatedDocument.originalName(), storedObject.storageKey(),
                validatedDocument.contentType(), validatedDocument.extension(), storedObject.sizeBytes(), sha256);
        try {
            Document savedDocument = documentRepository.saveAndFlush(document);
            if (documentIngestionRequestService != null) {
                documentIngestionRequestService.requestInitialIngestion(savedDocument);
            }
            return toResponse(savedDocument);
        }
        catch (DataIntegrityViolationException exception) {
            deleteAfterFailedPersistence(storedObject.storageKey());
            throw duplicateDocument(exception);
        }
        catch (RuntimeException exception) {
            deleteAfterFailedPersistence(storedObject.storageKey());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public DocumentPageResponse list(long knowledgeBaseId, int page, int size) {
        requireKnowledgeBase(knowledgeBaseId);
        Page<Document> result = documentRepository.findByKnowledgeBaseId(knowledgeBaseId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
        List<DocumentResponse> content = result.getContent().stream().map(this::toResponse).toList();
        return new DocumentPageResponse(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public DocumentResponse getById(long knowledgeBaseId, long documentId) {
        requireKnowledgeBase(knowledgeBaseId);
        return toResponse(findDocument(knowledgeBaseId, documentId));
    }

    @Transactional
    public void delete(long knowledgeBaseId, long documentId) {
        requireKnowledgeBase(knowledgeBaseId);
        Document document = findDocument(knowledgeBaseId, documentId);
        documentRepository.delete(document);
        documentRepository.flush();
        try {
            documentStorage.delete(document.getStorageKey());
        }
        catch (DocumentStorageException exception) {
            throw new BusinessException(DocumentErrorCode.STORAGE_FAILURE,
                    DocumentErrorCode.STORAGE_FAILURE.defaultMessage(), exception);
        }
    }

    private void requireKnowledgeBase(long knowledgeBaseId) {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    private Document findDocument(long knowledgeBaseId, long documentId) {
        return documentRepository.findByIdAndKnowledgeBaseId(documentId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
    }

    private StoredObject store(UploadDocumentCommand command, String extension) {
        try (InputStream input = command.content().getInputStream()) {
            return documentStorage.store(input, extension, command.sizeBytes());
        }
        catch (IOException | DocumentStorageException exception) {
            throw new BusinessException(DocumentErrorCode.STORAGE_FAILURE,
                    DocumentErrorCode.STORAGE_FAILURE.defaultMessage(), exception);
        }
    }

    private String calculateSha256(UploadDocumentCommand command) {
        MessageDigest digest = sha256Digest();
        try (InputStream input = command.content().getInputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (IOException exception) {
            throw new BusinessException(DocumentErrorCode.INVALID_FILE_CONTENT,
                    DocumentErrorCode.INVALID_FILE_CONTENT.defaultMessage(), exception);
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private BusinessException duplicateDocument() {
        return new BusinessException(DocumentErrorCode.DUPLICATE_DOCUMENT);
    }

    private BusinessException duplicateDocument(Throwable cause) {
        return new BusinessException(DocumentErrorCode.DUPLICATE_DOCUMENT,
                DocumentErrorCode.DUPLICATE_DOCUMENT.defaultMessage(), cause);
    }

    private void deleteAfterFailedPersistence(String storageKey) {
        try {
            documentStorage.delete(storageKey);
        }
        catch (DocumentStorageException exception) {
            LOGGER.error("文档元数据持久化失败后，补偿删除已存储文件失败", exception);
        }
    }

    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(document.getId(), document.getKnowledgeBaseId(), document.getOriginalName(),
                document.getContentType(), document.getExtension(), document.getSizeBytes(), document.getSha256(),
                document.getStatus(), document.getErrorCode(), document.getErrorMessage(), document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
