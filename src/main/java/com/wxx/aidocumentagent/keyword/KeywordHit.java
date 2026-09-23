package com.wxx.aidocumentagent.keyword;

/** 关键词检索命中，只包含可引用的 chunk 元数据和 BM25 分数。 */
public record KeywordHit(
        long chunkId,
        long knowledgeBaseId,
        long documentId,
        String content,
        String title,
        String sectionTitle,
        Integer pageFrom,
        Integer pageTo,
        String contentHash,
        double score) {
}
