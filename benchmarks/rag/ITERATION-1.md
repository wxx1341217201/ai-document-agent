# RAG 准确率第一轮迭代（2026-10-12）

本轮优先改善检索证据覆盖与上下文保留。参考答案、必需事实、可接受引用范围、判定阈值及人工最终判定规则保持固定；没有将关键词匹配改成最终准确率。

## 改动

- 两路候选数量分别读取 `app.retrieval.vector-top-k` / `keyword-top-k`，至少为请求 TopK。最终上下文数量仍由请求 TopK 决定。生产默认候选 20；benchmark 脚本默认候选 40、最终 8。
- 增加确定性检索计划，最多三个问题：保留原问题，拆分复合问题中的原文子句；多轮指代题补入最近用户问题的主题。助手历史不进入检索扩展，不调用额外 ChatModel，不使用 benchmark 标签或参考答案规划问题。
- Spring 装配并行执行扩展检索，共用 30s 截止时间，及时取消未完成任务。按各子问题排名轮流选择证据，去除重复 ID，严格保留知识库边界。部分子问题失败时保留成功证据并标记降级；全部失败返回既有业务错误，不泄露外部异常。
- 默认上下文字符预算 12,000 → 32,000，单 chunk 3,000 → 4,500；实际模型输入仍由 TokenCountEstimator 约束在窗口减输出预留内。保留来源多样性，相邻 chunk 仅在正文已完整出现在当前上下文时去重，保留新增事实。证据仍为原文前缀，数据库及冻结快照的真实性校验保持不变。

- 提示词要求逐项回答、比较时分别陈述各对象事实，引用必须直接支持当前陈述，明确标出缺少证据的部分。
- 每题报告新增 `retrievalTrace`，保存实际问题、策略版本和失败子问题数；其内容进入答案摘要。旧报告缺少该字段时保持旧序列化和人工复核摘要兼容。生成版本为 `m19b-quality-iteration1`，判定仍为 `m19b-human-final-v2`。
- 新增 `diagnose.py`，以同一数据集 SHA、完整语料指纹和判定规则比较原文证据可见率、引用结构失败、错误和逐题耗时。它不提供人工语义判断。

`redundant-overlap-characters` 配置项为兼容保留；当前逻辑不再根据边界重叠阈值删除含有新正文的 chunk。更多证据会增加输入量，扩展检索也会增加 embedding 请求；需要结合逐题耗时与预算选择配置。

## 复现

与原完整基线使用相同冻结数据集、同一供应商和现有只读索引；重排保持关闭，不把该实验解释为带 Cross-Encoder 的结果：

```powershell
.\scripts\run-rag-benchmark.ps1 -Mode Live -Rerank $false `
  -Dataset reports/m19b-20261012/rag-e48cc6cb-4889-4bba-a0b3-e381c194b1d2-dataset.jsonl `
  -CandidateTopK 40 -TopK 8 -QueryExpansion $true `
  -VectorTimeout 20s -KeywordTimeout 5s -TotalTimeout 25s `
  -ReportDirectory reports/rag-quality-iteration1 -UseHttpsProxy `
  -Deployment '填写实际环境；RAG quality iteration1; public literary and dummy corpus'

python benchmarks/rag/audit.py reports/rag-quality-iteration1/实际报告.json
python benchmarks/rag/diagnose.py reports/rag-quality-iteration1/实际报告.json `
  --baseline reports/m19b-20261012/rag-20261011T211026767Z-be57bc72.json
```

生产可通过 `RAG_QUERY_EXPANSION_ENABLED=false` 关闭扩展，或调整候选/上下文预算。复现旧的预算使用 `-CandidateTopK 8 -QueryExpansion $false -MaxContextCharacters 12000 -MaxChunkCharacters 3000`；这只复现配置，旧提示词/去重逻辑以旧版本原始报告为准。

## 验证与结果

- 最终全量后端 **433 tests，0 failures/errors/skips**，日志 `target/rag-quality-iteration1-final-all-tests.log`。新增 9 项检查覆盖候选/最终 TopK 分离、保留相邻独立事实与长 chunk 尾部、英中拆句及引导语排序、历史主题、合并去重/知识库边界、部分失败、统一截止时间/取消，以及复核摘要兼容。
- [旧报告离线回放](../../reports/rag-quality-iteration1/validation/rag-20261012T072945728Z-67b73b7f.json) / [独立审计 PASSED](../../reports/rag-quality-iteration1/validation/rag-20261012T072945728Z-67b73b7f-audit.json)：空白模板不产生人工判定，旧运行的答案、证据、摘要和指标保持不变。
- 新实测 runId：`e252c307-72fc-4adb-bbcd-c679ffd474bd`，北京时间 2026-10-12 15:35–16:00，真实生成 200/200，未执行 0，运行结束正常关闭 Spring/JVM。数据集 SHA-256 与原基线完全相同，完整语料前后稳定。
- [完整报告](../../reports/rag-quality-iteration1/rag-20261012T080032515Z-c2dc3b0a.md) / [JSON](../../reports/rag-quality-iteration1/rag-20261012T080032515Z-c2dc3b0a.json) / [错题 JSONL](../../reports/rag-quality-iteration1/rag-20261012T080032515Z-c2dc3b0a-failures.jsonl) / [独立审计 PASSED](../../reports/rag-quality-iteration1/rag-20261012T080032515Z-c2dc3b0a-audit.json) / [同口径诊断对照](../../reports/rag-quality-iteration1/rag-20261012T080032515Z-c2dc3b0a-diagnostic.json) / [人工复核材料](../../reports/rag-quality-iteration1/rag-20261012T080032515Z-c2dc3b0a-review-packet.md)。

| 指标 | 原完整基线 | 本轮 |
|---|---:|---:|
| 完整标注原文进入实际上下文 | 72/190（37.89%） | 128/190（67.37%） |
| 成功生成题中缺少标注支持 chunks | 90 | 54 |
| 已命中但标注原文被截断 | 25 | 0 |
| 引用硬性结构判错 | 96 | 60 |
| 执行错误 | 3 | 8（6 次超时 / 2 次不可用） |
| 检索降级 | 0 | 1 |
| 逐题耗时 P95，包含生成及证据核验 | 19.20s | 24.89s |
| 成功可回答题平均实际上下文字符数 | 11,135 | 22,337 |
| QA Accuracy 条件上界 | 91/190（47.89%） | 122/190（64.21%） |
| 最终 QA / 拒答准确率 | N/A / N/A | N/A / N/A |

| 题型 | 完整标注原文可见，前 → 后 | 引用结构判错，前 → 后 | 本轮调用错误 |
|---|---:|---:|---:|
| 单文档（90） | 54 → 73 | 28 → 20 | 3 |
| 多片段（40） | 8 → 24 | 23 → 10 | 4 |
| 跨文档（30） | 0 → 5 | 25 → 23 | 1 |
| 多轮（30） | 10 → 26 | 20 → 7 | 0 |

完整证据可见率使用全部 190 条可回答题作分母，错误也留在分母；引用结构判错与调用错误分开列示。上界假设剩余候选全部通过真实人工复核，**不是实测准确率**。192 条成功生成答案仍待标签/语义复核，人工复核为 0；8 次错误和 1 次降级使目标保持 `NOT_EVALUABLE`，没有声明 83% 达标。

这是固定数据、语料、判定与供应商配置的前后两次实测，多个实现改动合并验证，不能从该对照单独归因某项功能，也没有做重复采样或生产泛化验收。上下文输入量约为原来的两倍；本轮错误与耗时上升，没有将证据覆盖收益解释为稳定性或成本收益。

## 后续优先项

1. 查明模型超时/不可用和单次通道降级，验证更合适的上下文预算、输出长度与有限重试，记录质量和耗时的联合变化。
2. 跨文档仍有 24 条成功生成题缺少完整标注支持 chunks；用独立实验评估语义查询规划、语言转换和可用重排，不把关闭重排的结果当成带重排结果。
3. 完成真实人工标签验收及答案复核；随后补充不同主题的测试数据。当前 45 个文学/占位文档来源主题不代表生产分布。

## 本轮主要文件

```text
src/main/java/com/wxx/aidocumentagent/rag/application/RagRetrievalQueryPlanner.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagQueryRetrievalService.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagRetrievalTrace.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagQueryExecution.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagQueryApplicationService.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagContextBuilder.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagPromptTemplates.java
src/main/java/com/wxx/aidocumentagent/rag/RagConfiguration.java
src/main/java/com/wxx/aidocumentagent/rag/RagProperties.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyEvaluator.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyBenchmarkRunner.java
src/main/resources/application.yml
src/test/java/com/wxx/aidocumentagent/rag/application/RagQueryRetrievalServiceTest.java
src/test/java/com/wxx/aidocumentagent/rag/application/RagContextBuilderTest.java
src/test/java/com/wxx/aidocumentagent/rag/RagConfigurationStartupTest.java
src/test/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyEvaluatorTest.java
scripts/run-rag-benchmark.ps1
benchmarks/rag/audit.py
benchmarks/rag/diagnose.py
benchmarks/rag/prepare_review.py
.env.example
```

## 范围

本轮没有切换或训练模型、重新分片、重建索引或修改 `.env`。没有人工填充评审结论。语义查询规划、从长 chunk 中部选择带位置的证据片段、重排服务恢复及模型对照留待后续独立实验；本轮规则拆句不能理解所有复杂指代关系。
