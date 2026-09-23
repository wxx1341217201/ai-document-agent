package com.wxx.aidocumentagent.keyword;

/** Elasticsearch _source 的稳定文档模型；ES _id 固定为全局 chunkId。 */
public record KeywordDocument(
        long chunkId,
        long knowledgeBaseId,
        long documentId,
        String content,
        String title,
        String sectionTitle,
        Integer pageFrom,
        Integer pageTo,
        String contentHash) {

    public static final String CHUNK_ID = "chunkId";
    public static final String KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String DOCUMENT_ID = "documentId";
    public static final String CONTENT = "content";
    public static final String TITLE = "title";
    public static final String SECTION_TITLE = "sectionTitle";
    public static final String PAGE_FROM = "pageFrom";
    public static final String PAGE_TO = "pageTo";
    public static final String CONTENT_HASH = "contentHash";

    public KeywordDocument {
        if (chunkId <= 0 || knowledgeBaseId <= 0 || documentId <= 0) {
            throw new IllegalArgumentException("关键词索引标识必须大于0");
        }
        content = requireText(content, "content不能为空");
        contentHash = requireText(contentHash, "contentHash不能为空");
        title = nullableText(title);
        sectionTitle = nullableText(sectionTitle);
        if ((pageFrom == null) != (pageTo == null)
                || (pageFrom != null && (pageFrom < 1 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("关键词索引页码范围不合法");
        }
    }

    /** 文档 chunk 主键全局唯一，因此重复 upsert 必然覆盖同一 ES 文档。 */
    public String elasticsearchId() {
        return Long.toString(chunkId);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
