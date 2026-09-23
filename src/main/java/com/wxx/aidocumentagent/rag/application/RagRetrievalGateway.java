package com.wxx.aidocumentagent.rag.application;

import com.wxx.aidocumentagent.retrieval.RetrievalResult;

/** M11 与 M09/M10 混合检索之间的窄应用端口，便于在问答层独立校验结果。 */
@FunctionalInterface
public interface RagRetrievalGateway {

    RetrievalResult retrieve(long knowledgeBaseId, String question, int topK, boolean rerank);
}
