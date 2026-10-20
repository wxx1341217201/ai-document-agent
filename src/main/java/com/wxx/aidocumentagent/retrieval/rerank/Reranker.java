package com.wxx.aidocumentagent.retrieval.rerank;

import java.util.List;

import com.wxx.aidocumentagent.retrieval.RetrievedChunk;

/**
 * 对已按 RRF 排序的候选片段进行二阶段相关性排序的端口。
 * 实现必须校验远端结果和请求候选的一一对应关系；使用位置索引的供应商接口还须验证索引范围及唯一性。
 */
public interface Reranker {

    List<RankedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
