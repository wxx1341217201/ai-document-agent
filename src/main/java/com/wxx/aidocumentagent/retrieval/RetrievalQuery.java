package com.wxx.aidocumentagent.retrieval;

/**
 * 混合检索的统一输入。两个索引端口都必须从这里取得同一个 knowledgeBaseId，
 * 不能退化为无边界的全局查询。
 */
public record RetrievalQuery(
        long knowledgeBaseId,
        String query,
        int vectorTopK,
        int keywordTopK,
        int finalTopK) {

    public static final int MAX_TOP_K = 100;

    public RetrievalQuery {
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询文本不能为空");
        }
        requireTopK(vectorTopK, "vectorTopK");
        requireTopK(keywordTopK, "keywordTopK");
        requireTopK(finalTopK, "finalTopK");
    }

    private static void requireTopK(int value, String name) {
        if (value < 1 || value > MAX_TOP_K) {
            throw new IllegalArgumentException(name + "必须在1到" + MAX_TOP_K + "之间");
        }
    }
}
