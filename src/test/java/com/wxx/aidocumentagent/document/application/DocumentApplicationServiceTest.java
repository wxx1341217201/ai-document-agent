package com.wxx.aidocumentagent.document.application;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.api.dto.DocumentResponse;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.storage.DocumentStorage;
import com.wxx.aidocumentagent.document.storage.DocumentStorageException;
import com.wxx.aidocumentagent.document.storage.DocumentStorageProperties;
import com.wxx.aidocumentagent.document.storage.StoredObject;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentApplicationServiceTest {

    private static final long KNOWLEDGE_BASE_ID = 7L;
    private static final byte[] PDF_BYTES = "%PDF-1.7\nexample".getBytes(StandardCharsets.US_ASCII);
    private static final String STORAGE_KEY = "550e8400-e29b-41d4-a716-446655440000.pdf";

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentStorage documentStorage;

    private DocumentApplicationService service;

    @BeforeEach
    void setUp() {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setMaxFileSize(DataSize.ofMegabytes(1));
        service = new DocumentApplicationService(knowledgeBaseRepository, documentRepository, documentStorage,
                new DocumentUploadValidator(properties));
        lenient().when(knowledgeBaseRepository.existsById(KNOWLEDGE_BASE_ID)).thenReturn(true);
    }

    @Test
    void 保存通过校验的PDF并创建已上传元数据() {
        when(documentStorage.store(any(InputStream.class), eq("pdf"), eq((long) PDF_BYTES.length)))
                .thenReturn(new StoredObject(STORAGE_KEY, PDF_BYTES.length));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            setPersistentFields(document, 23L);
            return document;
        });

        DocumentResponse response = service.upload(KNOWLEDGE_BASE_ID,
                command("guide.pdf", "application/pdf", PDF_BYTES));

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).saveAndFlush(documentCaptor.capture());
        assertThat(documentCaptor.getValue().getKnowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(documentCaptor.getValue().getOriginalName()).isEqualTo("guide.pdf");
        assertThat(documentCaptor.getValue().getStorageKey()).isEqualTo(STORAGE_KEY);
        assertThat(documentCaptor.getValue().getSha256()).isEqualTo(sha256(PDF_BYTES));
        assertThat(response.id()).isEqualTo(23L);
        assertThat(response.status()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void 未知知识库会在读取或保存文件前被拒绝() {
        when(knowledgeBaseRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.upload(99L, command("guide.pdf", "application/pdf", PDF_BYTES)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

        verify(documentStorage, never()).store(any(), any(), anyLong());
    }

    @Test
    void 访问存储前拒绝路径穿越文件名() {
        assertThatThrownBy(() -> service.upload(KNOWLEDGE_BASE_ID,
                command("../guide.pdf", "application/pdf", PDF_BYTES)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.INVALID_FILE_NAME));

        verify(documentStorage, never()).store(any(), any(), anyLong());
    }

    @Test
    void 拒绝不支持的扩展名() {
        assertThatThrownBy(() -> service.upload(KNOWLEDGE_BASE_ID,
                command("guide.exe", "application/octet-stream", PDF_BYTES)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.UNSUPPORTED_FILE_TYPE));

        verify(documentStorage, never()).store(any(), any(), anyLong());
    }

    @Test
    void 拒绝空文件和超限文件() {
        assertThatThrownBy(() -> service.upload(KNOWLEDGE_BASE_ID,
                command("empty.txt", "text/plain", new byte[0])))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.EMPTY_FILE));

        byte[] oversized = new byte[1_048_577];
        assertThatThrownBy(() -> service.upload(KNOWLEDGE_BASE_ID,
                command("large.txt", "text/plain", oversized)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode().code()).isEqualTo("FILE_TOO_LARGE"));
    }

    @Test
    void 拒绝文件签名与声明类型不匹配的PDF() {
        byte[] invalidPdf = "不是PDF文件".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.upload(KNOWLEDGE_BASE_ID,
                command("guide.pdf", "application/pdf", invalidPdf)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.INVALID_FILE_CONTENT));
    }

    @Test
    void 拒绝同一知识库中的重复内容() {
        Document existing = uploadedDocument(23L, KNOWLEDGE_BASE_ID, STORAGE_KEY, sha256(PDF_BYTES));
        when(documentRepository.findByKnowledgeBaseIdAndSha256(KNOWLEDGE_BASE_ID, sha256(PDF_BYTES)))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upload(KNOWLEDGE_BASE_ID,
                command("guide.pdf", "application/pdf", PDF_BYTES)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.DUPLICATE_DOCUMENT));

        verify(documentStorage, never()).store(any(), any(), anyLong());
    }

    @Test
    void 不暴露其他知识库的文档() {
        when(documentRepository.findByIdAndKnowledgeBaseId(23L, KNOWLEDGE_BASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(KNOWLEDGE_BASE_ID, 23L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.DOCUMENT_NOT_FOUND));

        verify(documentRepository).findByIdAndKnowledgeBaseId(23L, KNOWLEDGE_BASE_ID);
    }

    @Test
    void 仅在元数据删除已刷写后删除存储对象() {
        Document document = uploadedDocument(23L, KNOWLEDGE_BASE_ID, STORAGE_KEY, sha256(PDF_BYTES));
        when(documentRepository.findByIdAndKnowledgeBaseId(23L, KNOWLEDGE_BASE_ID)).thenReturn(Optional.of(document));

        service.delete(KNOWLEDGE_BASE_ID, 23L);

        InOrder inOrder = inOrder(documentRepository, documentStorage);
        inOrder.verify(documentRepository).delete(document);
        inOrder.verify(documentRepository).flush();
        inOrder.verify(documentStorage).delete(STORAGE_KEY);
    }

    @Test
    void 物理删除失败时保留元数据() {
        Document document = uploadedDocument(23L, KNOWLEDGE_BASE_ID, STORAGE_KEY, sha256(PDF_BYTES));
        when(documentRepository.findByIdAndKnowledgeBaseId(23L, KNOWLEDGE_BASE_ID)).thenReturn(Optional.of(document));
        doThrow(new DocumentStorageException("磁盘不可用")).when(documentStorage).delete(STORAGE_KEY);

        assertThatThrownBy(() -> service.delete(KNOWLEDGE_BASE_ID, 23L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.STORAGE_FAILURE));

        verify(documentRepository).flush();
    }

    private UploadDocumentCommand command(String originalName, String contentType, byte[] content) {
        return new UploadDocumentCommand(originalName, contentType, content.length,
                () -> new ByteArrayInputStream(content));
    }

    private Document uploadedDocument(long id, long knowledgeBaseId, String storageKey, String sha256) {
        Document document = Document.uploaded(knowledgeBaseId, "guide.pdf", storageKey, "application/pdf", "pdf",
                PDF_BYTES.length, sha256);
        setPersistentFields(document, id);
        return document;
    }

    private void setPersistentFields(Document document, long id) {
        ReflectionTestUtils.setField(document, "id", id);
        ReflectionTestUtils.setField(document, "createdAt", LocalDateTime.of(2026, 9, 20, 10, 0));
        ReflectionTestUtils.setField(document, "updatedAt", LocalDateTime.of(2026, 9, 20, 10, 0));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
