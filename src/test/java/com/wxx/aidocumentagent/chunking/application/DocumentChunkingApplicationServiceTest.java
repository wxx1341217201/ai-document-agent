package com.wxx.aidocumentagent.chunking.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wxx.aidocumentagent.chunking.ChunkingOptions;
import com.wxx.aidocumentagent.chunking.ChunkingProperties;
import com.wxx.aidocumentagent.chunking.ChunkingStrategyRegistry;
import com.wxx.aidocumentagent.chunking.ChunkingUnit;
import com.wxx.aidocumentagent.chunking.FixedWindowChunkingStrategy;
import com.wxx.aidocumentagent.chunking.ParagraphChunkingStrategy;
import com.wxx.aidocumentagent.chunking.domain.ChunkingErrorCode;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentChunkingApplicationServiceTest {

    private static final long KNOWLEDGE_BASE_ID = 7L;
    private static final long DOCUMENT_ID = 23L;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    private DocumentChunkingApplicationService service;

    @BeforeEach
    void setUp() {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setPersistenceBatchSize(2);
        ChunkingStrategyRegistry registry = new ChunkingStrategyRegistry(List.of(
                new FixedWindowChunkingStrategy(), new ParagraphChunkingStrategy()));
        service = new DocumentChunkingApplicationService(knowledgeBaseRepository, documentRepository,
                documentChunkRepository, registry, properties);
        lenient().when(knowledgeBaseRepository.existsById(KNOWLEDGE_BASE_ID)).thenReturn(true);
        lenient().when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(Document.class)));
    }

    @Test
    void 在同一知识库内先替换旧chunk再按边界批量保存新chunk() {
        when(documentChunkRepository.deleteByKnowledgeBaseIdAndDocumentId(KNOWLEDGE_BASE_ID, DOCUMENT_ID))
                .thenReturn(2);

        DocumentChunkingResult result = service.replace(KNOWLEDGE_BASE_ID, DOCUMENT_ID, document("abcdefghi"),
                FixedWindowChunkingStrategy.STRATEGY_NAME, new ChunkingOptions(ChunkingUnit.CHARACTER, 3, 0));

        assertThat(result.replacedChunkCount()).isEqualTo(2);
        assertThat(result.chunks()).extracting(chunk -> chunk.content()).containsExactly("abc", "def", "ghi");
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<DocumentChunk>> batches = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(documentChunkRepository, times(2)).saveAll(batches.capture());
        verify(documentChunkRepository, times(2)).flush();
        assertThat(batches.getAllValues()).hasSize(2);
        DocumentChunk first = batches.getAllValues().getFirst().getFirst();
        assertThat(first.getKnowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(first.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(first.getChunkIndex()).isZero();
        assertThat(first.getContentHash()).isEqualTo(result.chunks().getFirst().contentHash());

        InOrder order = inOrder(documentChunkRepository);
        order.verify(documentChunkRepository).deleteByKnowledgeBaseIdAndDocumentId(KNOWLEDGE_BASE_ID, DOCUMENT_ID);
        order.verify(documentChunkRepository).saveAll(any());
    }

    @Test
    void 其他知识库的文档不能触发删除或写入() {
        when(documentRepository.findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(KNOWLEDGE_BASE_ID, DOCUMENT_ID, document("正文")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(DocumentErrorCode.DOCUMENT_NOT_FOUND));

        verify(documentRepository).findByIdAndKnowledgeBaseId(DOCUMENT_ID, KNOWLEDGE_BASE_ID);
        verify(documentChunkRepository, never()).deleteByKnowledgeBaseIdAndDocumentId(anyLong(), anyLong());
        verify(documentChunkRepository, never()).saveAll(any());
    }

    @Test
    void 未知知识库在访问文档或chunk前被拒绝() {
        when(knowledgeBaseRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.replace(99L, DOCUMENT_ID, document("正文")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

        verify(documentRepository, never()).findByIdAndKnowledgeBaseId(anyLong(), anyLong());
        verify(documentChunkRepository, never()).deleteByKnowledgeBaseIdAndDocumentId(anyLong(), anyLong());
    }

    @Test
    void 无效策略在删除旧chunk前失败() {
        assertThatThrownBy(() -> service.replace(KNOWLEDGE_BASE_ID, DOCUMENT_ID, document("正文"), "missing",
                new ChunkingOptions(ChunkingUnit.TOKEN, 4, 1)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ChunkingErrorCode.CHUNKING_STRATEGY_NOT_FOUND));

        verify(documentChunkRepository, never()).deleteByKnowledgeBaseIdAndDocumentId(anyLong(), anyLong());
    }

    private ParsedDocument document(String text) {
        return new ParsedDocument(text, List.of(), Map.of(), List.of(), List.of());
    }
}
