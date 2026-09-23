package com.wxx.aidocumentagent.vector.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.vector.ChunkVector;
import com.wxx.aidocumentagent.vector.VectorHit;
import com.wxx.aidocumentagent.vector.VectorIndex;
import com.wxx.aidocumentagent.vector.VectorIndexExceptionTranslator;
import com.wxx.aidocumentagent.vector.VectorProperties;
import com.wxx.aidocumentagent.vector.VectorQuery;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/** Spring AI Qdrant VectorStore 的基础设施适配器。 */
public final class QdrantVectorIndex implements VectorIndex {

    private final VectorStore vectorStore;
    private final VectorProperties properties;
    private final VectorIndexExceptionTranslator exceptionTranslator;

    public QdrantVectorIndex(VectorStore vectorStore, VectorProperties properties,
                             VectorIndexExceptionTranslator exceptionTranslator) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.exceptionTranslator = exceptionTranslator;
    }

    @Override
    public void upsert(List<ChunkVector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return;
        }
        List<ChunkVector> copy = List.copyOf(vectors);
        int batchSize = properties.getEmbedding().getBatchSize();
        try {
            for (int start = 0; start < copy.size(); start += batchSize) {
                int end = Math.min(copy.size(), start + batchSize);
                vectorStore.add(copy.subList(start, end).stream().map(this::toDocument).toList());
            }
        }
        catch (RuntimeException exception) {
            throw exceptionTranslator.translateVectorStore(exception);
        }
    }

    @Override
    public List<VectorHit> search(VectorQuery query) {
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query(query.text())
                .topK(query.topK())
                .similarityThreshold(query.similarityThreshold())
                .filterExpression(filter.eq(ChunkVector.KNOWLEDGE_BASE_ID,
                        Long.toString(query.knowledgeBaseId())).build())
                .build();
        try {
            List<VectorHit> hits = new ArrayList<>();
            for (Document document : vectorStore.similaritySearch(request)) {
                VectorHit hit = toVectorHit(document);
                // Qdrant 过滤器是第一道边界；结果映射时再检查一次，防止错误或旧数据跨库泄漏。
                if (hit.knowledgeBaseId() == query.knowledgeBaseId()) {
                    hits.add(hit);
                }
            }
            return List.copyOf(hits);
        }
        catch (RuntimeException exception) {
            throw exceptionTranslator.translateVectorStore(exception);
        }
    }

    @Override
    public void deleteByDocument(long knowledgeBaseId, long documentId) {
        if (knowledgeBaseId <= 0 || documentId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId和documentId必须大于0");
        }
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        try {
            vectorStore.delete(filter.and(
                    filter.eq(ChunkVector.KNOWLEDGE_BASE_ID, Long.toString(knowledgeBaseId)),
                    filter.eq(ChunkVector.DOCUMENT_ID, Long.toString(documentId))).build());
        }
        catch (RuntimeException exception) {
            throw exceptionTranslator.translateVectorStore(exception);
        }
    }

    private Document toDocument(ChunkVector vector) {
        if (!properties.getEmbedding().getModel().equals(vector.embeddingModel())) {
            throw new IllegalArgumentException("chunk embeddingModel与当前向量索引配置不一致");
        }
        return Document.builder()
                .id(vector.pointId())
                .text(vector.content())
                .metadata(vector.payload())
                .build();
    }

    private VectorHit toVectorHit(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new VectorHit(document.getId(), requiredLong(metadata, ChunkVector.CHUNK_ID),
                requiredLong(metadata, ChunkVector.DOCUMENT_ID), requiredLong(metadata, ChunkVector.KNOWLEDGE_BASE_ID),
                requiredInteger(metadata, ChunkVector.CHUNK_INDEX), nullableInteger(metadata, ChunkVector.PAGE_FROM),
                nullableInteger(metadata, ChunkVector.PAGE_TO), requiredText(metadata, ChunkVector.CONTENT_HASH),
                requiredText(metadata, ChunkVector.EMBEDDING_MODEL), document.getText(),
                document.getScore() == null ? 0D : document.getScore());
    }

    private long requiredLong(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Qdrant payload '" + key + "'不是long", exception);
            }
        }
        throw new IllegalArgumentException("Qdrant payload缺少'" + key + "'");
    }

    private int requiredInteger(Map<String, Object> metadata, String key) {
        long value = requiredLong(metadata, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Qdrant payload '" + key + "'超出int范围");
        }
        return (int) value;
    }

    private Integer nullableInteger(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null || (value instanceof String text && text.isBlank()) ? null : requiredInteger(metadata, key);
    }

    private String requiredText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException("Qdrant payload缺少'" + key + "'");
    }
}
