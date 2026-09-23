package com.wxx.aidocumentagent.vector;

/** 不提供可选 knowledgeBaseId：每次语义检索都必须带知识库边界。 */
public record VectorQuery(long knowledgeBaseId, String text, int topK, double similarityThreshold) {

    public VectorQuery {
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId必须大于0");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("查询文本不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK必须大于0");
        }
        if (!Double.isFinite(similarityThreshold) || similarityThreshold < -1D || similarityThreshold > 1D) {
            throw new IllegalArgumentException("相似度阈值必须在[-1, 1]之间");
        }
    }
}
