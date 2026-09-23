package com.wxx.aidocumentagent.keyword;

/** knowledgeBaseId 是必填过滤边界，而不是可选的全文查询词。 */
public record KeywordQuery(long knowledgeBaseId, String text, int topK) {

    public KeywordQuery {
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
