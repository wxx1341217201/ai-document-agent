package com.wxx.aidocumentagent.vector;

import java.util.List;

/** 业务层的向量索引端口，不向应用层暴露具体向量数据库或 Spring AI 类型。 */
public interface VectorIndex {

    void upsert(List<ChunkVector> vectors);

    List<VectorHit> search(VectorQuery query);

    void deleteByDocument(long knowledgeBaseId, long documentId);
}
