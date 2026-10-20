package com.wxx.aidocumentagent.keyword;

/** knowledgeBaseId 是必填过滤边界，而不是可选的全文查询词。 */
public record KeywordQuery(long knowledgeBaseId, String text, int topK, java.util.Set<Long> documentIds) {

    public KeywordQuery(long knowledgeBaseId, String text, int topK) {
        this(knowledgeBaseId, text, topK, java.util.Set.of());
    }

    public KeywordQuery {
        documentIds = java.util.Set.copyOf(documentIds);
        if (documentIds.stream().anyMatch(id -> id <= 0)) throw new IllegalArgumentException("文档范围无效");
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("关键词检索文本不能为空");
        }
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK必须在1到100之间");
        }
    }
}
