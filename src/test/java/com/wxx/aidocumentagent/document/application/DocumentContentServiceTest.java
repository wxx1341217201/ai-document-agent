package com.wxx.aidocumentagent.document.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.storage.DocumentStorage;
import com.wxx.aidocumentagent.document.storage.DocumentStorageException;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentContentServiceTest {
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final DocumentContentService service = new DocumentContentService(knowledgeBases, documents, chunks, storage);

    @Test
    void readsOnlyTheRequestedScopedPageInDocumentOrder() {
        when(knowledgeBases.existsById(8L)).thenReturn(true);
        when(documents.existsByIdAndKnowledgeBaseId(15L, 8L)).thenReturn(true);
        var page = PageRequest.of(1, 50, Sort.by(Sort.Direction.ASC, "chunkIndex"));
        var chunk = mock(DocumentChunkRepository.ContentSummary.class);
        when(chunk.getId()).thenReturn(500L);
        when(chunk.getChunkIndex()).thenReturn(50);
        when(chunk.getContent()).thenReturn("真实正文");
        when(chunk.getPageFrom()).thenReturn(null);
        when(chunks.findAllProjectedByKnowledgeBaseIdAndDocumentId(8L, 15L, page))
                .thenReturn(new PageImpl<>(List.of(chunk), page, 1125));
        var result = service.chunks(8, 15, 1, 50);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalElements()).isEqualTo(1125);
        assertThat(result.totalPages()).isEqualTo(23);
        assertThat(result.content()).singleElement().satisfies(item -> {
            assertThat(item.chunkIndex()).isEqualTo(50);
            assertThat(item.content()).isEqualTo("真实正文");
            assertThat(item.pageFrom()).isNull();
        });
        verify(chunks).findAllProjectedByKnowledgeBaseIdAndDocumentId(8L, 15L, page);
        verifyNoInteractions(storage);
    }

    @Test
    void existingDocumentWithoutChunksReturnsAnEmptyPage() {
        when(knowledgeBases.existsById(8L)).thenReturn(true);
        when(documents.existsByIdAndKnowledgeBaseId(15L, 8L)).thenReturn(true);
        var page = PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "chunkIndex"));
        when(chunks.findAllProjectedByKnowledgeBaseIdAndDocumentId(8L, 15L, page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));
        assertThat(service.chunks(8, 15, 0, 50).content()).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void wrongKnowledgeBaseOrDocumentCannotReadChunksOrStorage() {
        when(knowledgeBases.existsById(7L)).thenReturn(true);
        when(documents.findByIdAndKnowledgeBaseId(15L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.chunks(7, 15, 0, 50)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        assertThatThrownBy(() -> service.download(7, 15)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        verifyNoInteractions(chunks, storage);
    }

    @Test
    void missingKnowledgeBaseIsRejectedBeforeReadingDocuments() {
        assertThatThrownBy(() -> service.chunks(99, 15, 0, 50)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        assertThatThrownBy(() -> service.download(99, 15)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(documents, chunks, storage);
    }

    private void existingFile() {
        when(knowledgeBases.existsById(8L)).thenReturn(true);
        var document = Document.uploaded(8L, "原书.txt", "server-only-key.txt", "text/plain", "txt", 3, "abc");
        when(documents.findByIdAndKnowledgeBaseId(15L, 8L)).thenReturn(Optional.of(document));
    }

    @Test
    void downloadUsesOnlyTheScopedDocumentsStoredKeyAndPreservesBytes() throws Exception {
        existingFile();
        when(storage.load("server-only-key.txt")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        var file = service.download(8, 15);
        assertThat(file.originalName()).isEqualTo("原书.txt");
        assertThat(file.sizeBytes()).isEqualTo(3);
        try (var input = file.content()) { assertThat(input.readAllBytes()).containsExactly(1, 2, 3); }
        verify(storage).load("server-only-key.txt");
        verifyNoInteractions(chunks);
    }

    @Test
    void absentOriginalFileHasAnExplicitNotFoundErrorWithoutExposingPaths() {
        existingFile();
        when(storage.load("server-only-key.txt")).thenThrow(new DocumentStorageException("private path",
                new NoSuchFileException("/private/source.txt")));
        assertThatThrownBy(() -> service.download(8, 15)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(DocumentErrorCode.FILE_NOT_FOUND);
            assertThat(error.getMessage()).doesNotContain("private", "source.txt");
        });
    }

    @Test
    void otherStorageErrorsAreReportedAsReadFailures() {
        existingFile();
        when(storage.load("server-only-key.txt")).thenThrow(new DocumentStorageException("private path", new IOException()));
        assertThatThrownBy(() -> service.download(8, 15)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(DocumentErrorCode.READ_FAILURE));
    }
}
