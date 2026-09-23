package com.wxx.aidocumentagent.retrieval.rerank;

import java.util.List;

import com.wxx.aidocumentagent.retrieval.RetrievedChunk;

/**
 * 对已按 RRF 排序的候选片段进行二阶段相关性排序的端口。
 * 实现必须按 chunkId 验证服务响应，不能将返回分数按数组下标绑定到候选片段。
 */
public interface Reranker {

    List<RankedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
