package com.wxx.aidocumentagent.keyword;

import java.util.List;

/**
 * 关键词检索的应用端口。调用方永远需要提供 knowledgeBaseId，基础设施适配器不允许无边界检索。
 */
public interface KeywordIndex {

    void upsert(List<KeywordDocument> chunks);

    List<KeywordHit> search(KeywordQuery query);

    void deleteByDocument(long knowledgeBaseId, long documentId);
}
