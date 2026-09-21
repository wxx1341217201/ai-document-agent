package com.wxx.aidocumentagent.chunking.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.chunking.ChunkingOptions;
import com.wxx.aidocumentagent.chunking.ChunkingProperties;
import com.wxx.aidocumentagent.chunking.ChunkingStrategy;
import com.wxx.aidocumentagent.chunking.ChunkingStrategyRegistry;
import com.wxx.aidocumentagent.chunking.TextChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentErrorCode;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.parser.ParsedDocument;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受知识库边界保护的切分替换入口。先完成内存中的切分，再删除旧 chunk；任意持久化失败会由事务回滚。
 */
@Service
public class DocumentChunkingApplicationService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ChunkingStrategyRegistry chunkingStrategyRegistry;
    private final ChunkingProperties chunkingProperties;

    public DocumentChunkingApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                              DocumentRepository documentRepository,
                                              DocumentChunkRepository documentChunkRepository,
                                              ChunkingStrategyRegistry chunkingStrategyRegistry,
                                              ChunkingProperties chunkingProperties) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.chunkingStrategyRegistry = chunkingStrategyRegistry;
        this.chunkingProperties = chunkingProperties;
    }

    @Transactional
    public DocumentChunkingResult replace(long knowledgeBaseId, long documentId, ParsedDocument parsedDocument) {
        return replace(knowledgeBaseId, documentId, parsedDocument, chunkingProperties.getStrategy(),
                chunkingProperties.toOptions());
    }

    /**
     * 供后续摄取流程以受控的策略和参数重新切分。旧、新数据始终处于同一个数据库事务中。
     */
    @Transactional
    public DocumentChunkingResult replace(long knowledgeBaseId, long documentId, ParsedDocument parsedDocument,
                                          String strategyName, ChunkingOptions options) {
        Objects.requireNonNull(parsedDocument, "parsedDocument不能为空");
        Objects.requireNonNull(options, "options不能为空");
        requireKnowledgeBase(knowledgeBaseId);
        requireDocument(knowledgeBaseId, documentId);

        ChunkingStrategy strategy = chunkingStrategyRegistry.strategyFor(strategyName);
        List<TextChunk> chunks = strategy.split(parsedDocument, options);

        // 切分成功后才开始删除；之后的删除和所有批量写入处于同一事务，失败不会留下新旧混合数据。
        int replacedChunkCount = documentChunkRepository.deleteByKnowledgeBaseIdAndDocumentId(knowledgeBaseId,
                documentId);
        saveInBatches(knowledgeBaseId, documentId, chunks);
        return new DocumentChunkingResult(knowledgeBaseId, documentId, strategy.name(), replacedChunkCount, chunks);
    }

    private void saveInBatches(long knowledgeBaseId, long documentId, List<TextChunk> chunks) {
        int batchSize = chunkingProperties.getPersistenceBatchSize();
        if (batchSize <= 0) {
            throw new IllegalStateException("chunk持久化批次大小必须大于0");
        }
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            List<DocumentChunk> batch = new ArrayList<>(end - start);
            for (TextChunk chunk : chunks.subList(start, end)) {
                batch.add(DocumentChunk.create(knowledgeBaseId, documentId, chunk));
            }
            documentChunkRepository.saveAll(batch);
            documentChunkRepository.flush();
        }
    }

    private void requireKnowledgeBase(long knowledgeBaseId) {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    private void requireDocument(long knowledgeBaseId, long documentId) {
        if (documentRepository.findByIdAndKnowledgeBaseId(documentId, knowledgeBaseId).isEmpty()) {
            throw new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND);
        }
    }
}
