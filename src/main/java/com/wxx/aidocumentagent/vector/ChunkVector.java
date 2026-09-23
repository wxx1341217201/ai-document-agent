package com.wxx.aidocumentagent.vector;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 已准备写入向量索引的 chunk；point ID 仅由全局 chunk ID 决定，重复 upsert 必然覆盖。 */
public record ChunkVector(
        long chunkId,
        long documentId,
        long knowledgeBaseId,
        int chunkIndex,
        Integer pageFrom,
        Integer pageTo,
        String contentHash,
        String embeddingModel,
        String content) {

    public static final String CHUNK_ID = "chunkId";
    public static final String DOCUMENT_ID = "documentId";
    public static final String KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String CHUNK_INDEX = "chunkIndex";
    public static final String PAGE_FROM = "pageFrom";
    public static final String PAGE_TO = "pageTo";
    public static final String CONTENT_HASH = "contentHash";
    public static final String EMBEDDING_MODEL = "embeddingModel";

    public ChunkVector {
        if (chunkId <= 0 || documentId <= 0 || knowledgeBaseId <= 0 || chunkIndex < 0) {
            throw new IllegalArgumentException("chunk向量标识或顺序不合法");
        }
        if ((pageFrom == null) != (pageTo == null) || (pageFrom != null && (pageFrom < 1 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("chunk向量页码范围不合法");
        }
        contentHash = requireText(contentHash, "contentHash不能为空");
        embeddingModel = requireText(embeddingModel, "embeddingModel不能为空");
        content = Objects.requireNonNull(content, "content不能为空");
    }

    public String pointId() {
        return UUID.nameUUIDFromBytes(("document-chunk:" + chunkId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Spring AI 2.0.1 的 Qdrant adapter 将 Long metadata 序列化为字符串；ID 显式使用十进制字符串，
     * 搜索和删除也使用同一表示，避免跨知识库过滤出现类型不匹配。
     */
    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(CHUNK_ID, Long.toString(chunkId));
        payload.put(DOCUMENT_ID, Long.toString(documentId));
        payload.put(KNOWLEDGE_BASE_ID, Long.toString(knowledgeBaseId));
        payload.put(CHUNK_INDEX, chunkIndex);
        // Spring AI Document metadata forbids null values. Empty string retains an explicit nullable payload field.
        payload.put(PAGE_FROM, pageFrom == null ? "" : pageFrom);
        payload.put(PAGE_TO, pageTo == null ? "" : pageTo);
        payload.put(CONTENT_HASH, contentHash);
        payload.put(EMBEDDING_MODEL, embeddingModel);
        return Map.copyOf(payload);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
