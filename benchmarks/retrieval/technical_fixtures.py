"""Source-based technical annotation drafts. Never reads retrieval predictions."""
import json
from pathlib import Path


ROOT = Path(__file__).parent
ANCHORS = [
    (900001, 90001, "向量维度不匹配", "当 embedding 返回的向量长度与 collection 配置不同，例如将 1536 维旧向量写入 1024 维 collection，使用 VECTOR_DIMENSION_MISMATCH。它是不可重试的配置错误；应核对 embedding dimensions 和 Qdrant collection，不能靠重试修复。", "src/main/java/com/wxx/aidocumentagent/vector/VectorIndexErrorCode.java",
     "VECTOR_DIMENSION_MISMATCH 是否允许自动重试？", "旧向量长度和新集合不一致时该检查什么？", "嵌入长度对不上索引规格，反复尝试能修好吗？", "1536 维向量写入 1024 维集合失败，应处理维度配置还是 embedding 限流？"),
    (900002, 90001, "嵌入限流", "Embedding HTTP 429 映射为 EMBEDDING_RATE_LIMITED，属于可重试错误。它与 HTTP 401/403 的 EMBEDDING_AUTHENTICATION_FAILED 不同：限流需要等待额度窗口或降低请求速率，而鉴权失败需要核对密钥。", "src/main/java/com/wxx/aidocumentagent/vector/VectorIndexExceptionTranslator.java",
     "Embedding 的 HTTP 429 对应哪个错误码？", "向量模型因短时间请求太多而拒绝调用，属于什么问题？", "嵌入服务说访问太频繁，该查口令还是降低调用频率？", "HTTP 429 应映射 EMBEDDING_RATE_LIMITED 还是 EMBEDDING_AUTHENTICATION_FAILED？"),
    (900003, 90001, "嵌入鉴权", "Embedding HTTP 401 或 403 映射为 EMBEDDING_AUTHENTICATION_FAILED，属于不可重试错误。应检查 API key 与授权；HTTP 429 则是可重试的 EMBEDDING_RATE_LIMITED。不能把鉴权失败当成网络超时。", "src/main/java/com/wxx/aidocumentagent/vector/VectorIndexExceptionTranslator.java",
     "EMBEDDING_AUTHENTICATION_FAILED 是否可重试？", "模型服务不接受调用者身份时需要检查什么？", "嵌入接口拒绝认证，重新提交同一个口令会解决吗？", "Embedding HTTP 403 是鉴权失败还是 QDRANT_TIMEOUT？"),
    (900004, 90002, "重排熔断", "Cross-Encoder 默认连续失败 3 次后打开熔断器，默认 circuit-open-duration 为 30s。熔断期间回退 RRF 排序；重排降级必须记录 rerankingDegraded，不能当成成功的 Cross-Encoder 测量。", "src/main/java/com/wxx/aidocumentagent/retrieval/rerank/RerankingProperties.java",
     "默认 circuit-failure-threshold 和 circuit-open-duration 分别是多少？", "重排服务连续失败时检索顺序如何继续提供？", "重排连续出错三回之后，多长时间内会跳过远端调用？", "Cross-Encoder 熔断后使用 RRF 回退还是将问题交给 BM25 单路？"),
    (900005, 90002, "RRF 合并", "RRF 使用 rank 而不是直接加两个通道原始分数。每个候选的融合分数为 vectorWeight/(rrfK+vectorRank) 加 keywordWeight/(rrfK+keywordRank)，缺失的通道不贡献分数。rrfK 默认 60，两个权重默认各为 1.0；分数相同时按 chunkId 升序稳定排序。", "src/main/java/com/wxx/aidocumentagent/retrieval/HybridRetriever.java",
     "rrfK 默认值是多少，RRF 是相加原始分数还是排名倒数？", "怎样合并不同量纲的语义与词法检索结果？", "两路打分尺度不一样，融合时依靠名次还是裸分？", "RRF 使用 1/(60+rank) 还是把 BM25 分数直接与余弦分数相加？"),
    (900006, 90003, "知识库边界", "向量检索和关键词检索都携带 knowledgeBaseId 过滤。Qdrant payload 中知识库 ID 按字符串保存；索引适配器映射响应时再次检查知识库。重排前也按知识库筛选候选，防止另一个知识库的文本发送到外部重排服务。", "src/main/java/com/wxx/aidocumentagent/vector/infrastructure/QdrantVectorIndex.java",
     "Qdrant knowledgeBaseId 过滤值按字符串还是数字保存？", "为什么检索响应已经过滤了还要核对文档所属知识库？", "已做库过滤后，返回结果还须再次核对所属库吗？", "防止跨库文本发给 reranker 应靠 knowledgeBaseId 还是 documentId 单独过滤？"),
    (900007, 90004, "文档进度标识", "M18 文档进度订阅使用 resource=document、knowledgeBaseId 和 documentId。文档进度事件顶层 resourceId/executionId 使用摄取任务 UUID，实际文档主键 documentId 位于 payload。用户看到文档 ID 和摄取任务 ID 时，应按字段语义区分，不能互换两种编号。", "docs/m18-progress-websocket.md",
     "文档进度 payload.documentId 与摄取任务 UUID 是否相同？", "进度事件里的任务标识能直接当数据库文档主键吗？", "上传进度里的作业编号和文件记录号是一个东西吗？", "文档事件顶层任务 UUID 是 documentId，还是 payload.documentId 才是文档主键？"),
    (900008, 90004, "文档分片参数", "当前段落分片默认 chunk-size=900、overlap=120，window-unit=TOKEN。修改配置不会改变已经持久化并索引的 chunks；做 chunkSize/overlap 对照实验须重新分片和建立独立索引，并重新绑定相关 chunk 标注。", "src/main/resources/application.yml",
     "CHUNKING_CHUNK_SIZE 和 CHUNKING_OVERLAP 默认分别是多少？", "调整切片窗口后，已建好的索引是否会自动重新切片？", "把片段大小改小，旧知识库的内容块会立刻变化吗？", "修改 overlap 是自动重建旧 chunks，还是需要重新分片再建索引？"),
    (900009, 90005, "压测统计路径", "M19A retrieval_latency 统计 Query Embedding、Vector Search、BM25 Search、RRF Fusion 和 Cross-Encoder Rerank 的完整路径；并行通道按实际墙钟耗时计时。排除 ChatModel 答案生成，也排除 benchmark worker pool 的等待时间。预热至少 50 次，正式采样至少 500 次，并固定记录并发数。", "benchmarks/retrieval/README.md",
     "retrieval_latency 是否包含 ChatModel，最少预热和正式采样各多少次？", "衡量检索响应速度时应把回答文字生成时间算进去吗？", "测搜资料的尾延迟，要包括大模型写答案的时间吗？", "P95 的路径终点是 Cross-Encoder Rerank，还是 ChatModel 输出最后一个 token？"),
    (900010, 90005, "无答案与召回", "Recall@5 是 Top-5 命中至少一个 expectedChunkIds 的可回答问题数除以全部可回答问题数。无答案问题 expectedChunkIds=[]，answerable=false，独立统计空结果率，不进入 Recall、MRR 和 nDCG 的分母。失败请求保留为未命中，并使实验不可用于达标声明。", "src/main/java/com/wxx/aidocumentagent/retrieval/benchmark/RetrievalBenchmarkMetrics.java",
     "answerable=false 的问题进入 Recall@5 分母吗？", "没有相关文档的问题应该如何单独统计？", "库里没有答案的题，应算作普通召回失败吗？", "Recall@5 的分母是所有问题，还是只有可回答问题？"),
]


def build():
    corpus, cases = [], []
    for chunk_id, doc_id, title, content, source, *questions in ANCHORS:
        corpus.append(dict(chunkId=chunk_id, knowledgeBaseId=2, documentId=doc_id,
                           chunkIndex=sum(c['documentId'] == doc_id for c in corpus), content=content,
                           title="M19A technical benchmark manual", sectionTitle=title,
                           pageFrom=None, pageTo=None, sourceReference=source))
        for i, (tag, question) in enumerate(zip(('exact', 'semantic', 'chinese_synonym', 'distractor'), questions), 1):
            tags = [tag, 'technical']
            if chunk_id <= 900003:
                tags.append('error_code')
            if chunk_id in (900004, 900006, 900007, 900008):
                tags.append('identifier')
            cases.append(dict(id=f'T{chunk_id}-{i}', knowledgeBaseId=2, question=question,
                              answerable=True, expectedChunkIds=[chunk_id], tags=tags))
    for i, (ids, question) in enumerate([
        ([900002, 900003], '嵌入请求过多与身份认证失败，分别对应哪些错误，重试属性有何不同？'),
        ([900004, 900005], '重排熔断期间使用的融合算法如何计算候选分数？'),
        ([900006, 900007], '检索隔离使用的知识库编号与文档进度 payload 中的文档编号分别有什么作用？'),
        ([900009, 900010], '无答案题如何影响召回分母，检索耗时又为何不统计模型生成答案？'),
    ], 1):
        tags = ['technical', 'cross_context']
        if len({next(a[1] for a in ANCHORS if a[0] == x) for x in ids}) > 1:
            tags.append('cross_document')
        cases.append(dict(id=f'TX{i}', knowledgeBaseId=2, question=question, answerable=True,
                          expectedChunkIds=ids, tags=tags))
    for i, question in enumerate([
        '本项目生产 DashScope API 密钥的完整字符串是什么？',
        '本项目知识库负责人个人电话号码是什么？',
        '错误码 EMBEDDING_TEAPOT_PURPLE 的生产处理步骤是什么？',
        '线上第 72431 号文档昨天被谁删除了？',
    ], 1):
        cases.append(dict(id=f'TN{i}', knowledgeBaseId=2, question=question, answerable=False,
                          expectedChunkIds=[], tags=['technical', 'unanswerable']))
    for name, rows in [('supplemental-corpus.jsonl', corpus), ('supplemental-dataset.jsonl', cases)]:
        (ROOT / name).write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows), encoding='utf-8')
    return cases


if __name__ == '__main__':
    print(f'Wrote {len(build())} technical annotation drafts')
