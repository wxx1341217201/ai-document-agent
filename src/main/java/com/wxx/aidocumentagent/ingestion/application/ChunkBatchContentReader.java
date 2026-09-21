package com.wxx.aidocumentagent.ingestion.application;

import java.util.List;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.ingestion.domain.IngestionErrorCode;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTask;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.DocumentBatchTaskRepository;
import com.wxx.aidocumentagent.ingestion.messaging.IngestionMessageValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 所有读取都同时带 knowledgeBaseId，防止 batchId/documentId 跨库串读。 */
@Service
public class ChunkBatchContentReader {

    private final DocumentBatchTaskRepository batchTaskRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    public ChunkBatchContentReader(DocumentBatchTaskRepository batchTaskRepository,
                                   DocumentRepository documentRepository,
                                   DocumentChunkRepository chunkRepository) {
        this.batchTaskRepository = batchTaskRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional(readOnly = true)
    public ChunkBatchIndexingRequest read(BatchWork work) {
        DocumentBatchTask task = batchTaskRepository.findByBatchIdAndKnowledgeBaseId(work.batchId(), work.knowledgeBaseId())
                .orElseThrow(() -> new IngestionMessageValidationException("batch任务不存在"));
        if (!task.getJobId().equals(work.jobId()) || task.getDocumentId() != work.documentId()
                || task.getChunkFrom() != work.chunkFrom() || task.getChunkTo() != work.chunkTo()) {
            throw new IngestionMessageValidationException(IngestionErrorCode.INGESTION_SCOPE_MISMATCH.defaultMessage());
        }
        if (documentRepository.findByIdAndKnowledgeBaseId(work.documentId(), work.knowledgeBaseId()).isEmpty()) {
            throw new IngestionMessageValidationException("batch所属文档不存在或知识库不匹配");
        }
        List<DocumentChunk> chunks = chunkRepository
                .findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(work.knowledgeBaseId(),
                        work.documentId(), work.chunkFrom(), work.chunkTo());
        int expectedCount = work.chunkTo() - work.chunkFrom() + 1;
        if (chunks.size() != expectedCount || !isContiguous(chunks, work.chunkFrom())) {
            throw new IllegalStateException(IngestionErrorCode.INGESTION_BATCH_CHUNK_MISMATCH.defaultMessage());
        }
        return new ChunkBatchIndexingRequest(work.batchId(), work.jobId(), work.knowledgeBaseId(), work.documentId(),
                work.chunkFrom(), work.chunkTo(), chunks.stream().map(this::toIndexableChunk).toList());
    }

    private boolean isContiguous(List<DocumentChunk> chunks, int chunkFrom) {
        for (int index = 0; index < chunks.size(); index++) {
            if (chunks.get(index).getChunkIndex() != chunkFrom + index) {
                return false;
            }
        }
        return true;
    }

    private ChunkBatchIndexingRequest.IndexableChunk toIndexableChunk(DocumentChunk chunk) {
        return new ChunkBatchIndexingRequest.IndexableChunk(chunk.getId(), chunk.getChunkIndex(), chunk.getContent(),
                chunk.getContentHash(), chunk.getTokenCount(), chunk.getPageFrom(), chunk.getPageTo(),
                chunk.getSectionTitle(), chunk.getMetadataJson());
    }
}
