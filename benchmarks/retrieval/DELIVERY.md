# M19A 交付与实测记录（2026-10-11）

评测实现、数据草稿、四组对照、压测及证据已交付。**91% Recall@5 和 490ms P95 均未达标**；不能把目标值写作实测成绩。真实标签仍待人工复核，当前结果状态为 `PROVISIONAL_PENDING_HUMAN_LABEL_REVIEW`。

## 有效真实基线

- [完整 Markdown 报告](../../reports/retrieval-20261011T135303009Z-785ea7d4.md) / [JSON](../../reports/retrieval-20261011T135303009Z-785ea7d4.json)。
- [独立指标审计 PASSED](../../reports/retrieval-20261011T135303009Z-785ea7d4-audit.json)。审计验证算术、冻结证据和样本完整性，不代替人工标签复核。
- [索引全量核验 PASSED](../../reports/retrieval-20261011T135303009Z-785ea7d4-index-validation.json)：Qdrant 259 points、Elasticsearch 259 documents；ID、知识库、文档、正文全部匹配，无缺失或额外 chunks，向量模型标记均为 `text-embedding-v4`。
- 数据集 248 条，其中可回答 234、无答案 14；相同冻结语料 259 chunks；每组质量请求 248 次，均无请求错误。

| 检索方案 | 命中 / 可回答 | Recall@5 | MRR@5 | nDCG@5 |
| --- | ---: | ---: | ---: | ---: |
| Vector Only | 173 / 234 | 73.93% | 0.6041 | 0.6317 |
| BM25 Only | 138 / 234 | 58.97% | 0.4395 | 0.4712 |
| Vector + BM25 + RRF | 182 / 234 | 77.78% | 0.6316 | 0.6629 |
| Vector + BM25 + RRF + Cross-Encoder | 210 / 234 | **89.74%** | **0.7871** | **0.8112** |

Cross-Encoder 总体 Recall 比 Vector 提高 **15.81 个百分点**，比 RRF 提高 **11.97 个百分点**。各 tag 的完整指标和增减表在报告中。以下退化同样保留：

| Tag | 可回答 | RRF Recall@5 | Cross-Encoder Recall@5 | 变化 |
| --- | ---: | ---: | ---: | ---: |
| technical | 44 | 100.00% | 97.73% | -2.27 pp |
| error_code | 12 | 100.00% | 91.67% | -8.33 pp |

语义改写和中文同义分桶的 Cross-Encoder Recall 分别为 85.45% 和 83.64%。14 个无答案问题在四组中都返回了非空候选；不进入 Recall/MRR/nDCG 分母。本模块没有运行 ChatModel，不能把检索结果当作正确拒答。跨文档分桶仅 3 个可回答问题，不能推断生产分布的整体效果。

## 延迟口径与环境

`retrieval_latency` = Query Embedding + Vector Search + BM25 Search + RRF Fusion + Cross-Encoder Rerank。检索两路并行；采用端到端墙钟耗时，排除最终 ChatModel 生成及 benchmark worker 排队，包含模型服务排队、网络和索引调用。

预热 **50** 次，正式采样 **500** 次，固定并发 **10**，错误 **0/500（0%）**。完整原始纳秒记录见 [observations.jsonl](../../reports/retrieval-20261011T135303009Z-785ea7d4-observations.jsonl)。

| 统计 | 完整 retrieval_latency（ms） | 其中 rerank（ms） |
| --- | ---: | ---: |
| P50 | 1579.43 | 909.30 |
| P95 | **2930.99** | 2193.13 |
| P99 | 3952.26 | 2657.34 |
| 平均 | 1740.47 | 1035.87 |
| 最大 | 4637.34 | 3888.38 |

- 真实 embedding：DashScope `text-embedding-v4`，1024 维；真实重排：DashScope `gte-rerank-v2`；均使用北京 `dashscope.aliyuncs.com` endpoint，区域依据 [官方 Base URL 文档](https://www.alibabacloud.com/help/en/model-studio/base-url)。
- Windows 11 amd64、Java 21.0.12、12 个逻辑 CPU；本机 Docker Qdrant/Elasticsearch；显式 HTTPS proxy `127.0.0.1:7890`。性能数值对应此环境。
- benchmark 专用 embedding 传输为 JDK HttpClient HTTP/1.1，超时 20s、零重试、无结果缓存；搜索复用生产 `QdrantVectorIndex`、`KeywordIndex` 和 `HybridRetriever`。这次数据不能表述为默认 Spring AI HTTP/2 SDK 环境的延迟。
- 两路 TopK=20，RRF k=60，权重各 1，重排候选上限 50，文本字符上限 4000，最终 TopK=5；重排零重试。记录 chunkSize=900、overlap=120，但使用已有语料，没有重新分片。
- MRR 明确截断于 Top-5；nDCG@5 使用 binary relevance。P50/P95/P99 使用成功样本的 nearest-rank；失败记录会保留，出现错误的运行禁止达标声明。

## 配置实验与本地回归

| 运行 | Vector / BM25 TopK | RRF k | 重排候选上限 | CE Recall@5 | P95 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 真实基线 `135303009Z-785ea7d4` | 20 / 20 | 60 | 50 | 89.74% | 2930.99ms | 有效；两项阈值未达；标签待人工复核 |
| 真实调参 `140257249Z-a320ce55` | 40 / 40 | 20 | 30 | — | — | 无效；25 个质量请求失败，未开始压测 |
| Fake 回归 `135508372Z-cd595271` | 20 / 20 | 60 | 50 | 53.85% | 218.76ms | REGRESSION_ONLY；不能作为模型质量/简历性能成绩 |

[调参 JSON](../../reports/retrieval-20261011T140257249Z-a320ce55.json) / [Markdown](../../reports/retrieval-20261011T140257249Z-a320ce55.md) 保留真实错误。后段重排返回 `REMOTE_REJECTED`，随后两个独立探测（2 个短文本、30 个冻结 passages）均返回 HTTP 400 / `Arrearage`。这是供应商欠费拒绝，不能解释为候选集扩大后的检索质量结论；没有把回退的 RRF 算作成功 CE，也没有从其他运行拼接压测样本。用户随后选择先交付现有实测结果。

[Fake 报告](../../reports/retrieval-20261011T135508372Z-cd595271.md) / [JSON](../../reports/retrieval-20261011T135508372Z-cd595271.json) / [审计 PASSED](../../reports/retrieval-20261011T135508372Z-cd595271-audit.json) 完成 50/500/10，错误为 0。其 embedding 为确定性 token hash，重排为词法重叠，分别附加 5ms 和 20ms；不进行语义模型推理。该 218.76ms 与真实外部环境的 2930.99ms 不能互换。

本地真实 ONNX 多语言 Cross-Encoder profile 也已实现，使用固定模型 revision、文件 SHA 和运行参数；本机此次运行在质量阶段发生请求失败，标为 `INVALID_QUALITY_RUN`，没有声称得到有效本地真实性能数字。

## 数据、实现与复现

- [retrieval-dataset.jsonl](retrieval-dataset.jsonl)：248 条、显式 `answerable` 和 `expectedChunkIds`，覆盖所有要求的 tag。可回答问题必须有标签；无答案必须为空数组，按 tag 单独统计。
- [技术补充语料](supplemental-corpus.jsonl)：10 个 fixture chunks，KB=2、ID=900001–900010，正文基于仓库代码编写，带 `sourceReference`。仅写 benchmark 索引；应用数据库只读。
- [Runner](../../src/main/java/com/wxx/aidocumentagent/retrieval/benchmark/RetrievalBenchmarkRunner.java)：四组生产检索端口、冻结证据、逐题 Top-5、tag buckets、增减表、原始 journal、50/500/固定并发、JSON/Markdown 及严格有效性判定。
- [DashScope HTTP adapter](../../src/main/java/com/wxx/aidocumentagent/retrieval/rerank/HttpCrossEncoderReranker.java)：遵循 [官方文本排序协议](https://www.alibabacloud.com/help/en/model-studio/text-rerank-api)，验证候选索引后映射回 chunk。通用与 Cohere provider 继续可用。
- [运行说明](README.md)、[脚本](../../scripts/run-retrieval-benchmark.ps1)、[独立审计](audit.py)、[本地模型准备](../../scripts/prepare-retrieval-local-model.ps1)。

完整 dataset SHA-256：`af7f5ac24a7bd3b00d044b103ac63e812563fdb0cea0fa49ae3079adc9c5a592`。完整 corpus fingerprint：`06c86b7741a45d2193dc04db4c19ec186ae9f5149602b4cfb8df7d9df7d4374d`。正式四组共 992 条质量记录，另有 50 条预热、500 条正式耗时记录。

标签是 **assistant-curated 的来源标注草稿**，不是已经完成的 248 条人工标注。原 200 条文学问题共享 45 个主题锚点；另增 48 条技术问题，不能据此推断 248 个独立生产问题的泛化效果。原 200 条及旧版数据继续保留，标签没有根据预测结果修改。

逐题人工复核入口为 [冻结复核模板](../../reports/retrieval-20261011T135303009Z-785ea7d4-label-review-template.jsonl)，当前 verified=0/248。模板绑定数据集、语料和逐题 SHA，初始 verified=null，不代填人工姓名。全部经真实人工核验后，用 `-LabelReview` 重新测量；改变 chunkSize/overlap 要先重新分片、重标 ID，再做新实验。

余额恢复后可按同一冻结快照重新测量基线和调参；现有索引已全量核验，因此不需要再次 bootstrap。若需重建索引，`-Bootstrap` 会先执行真实 embedding 并写入独立 benchmark 索引；失败会另存无效报告。

```powershell
$env:JAVA_HOME='D:\Java\jdk-21'
.\scripts\run-retrieval-benchmark.ps1 -Mode DashScope -QualityConcurrency 4 -UseHttpsProxy `
  -Corpus 'reports/retrieval-20261011T135303009Z-785ea7d4-corpus.jsonl' `
  -Dataset 'reports/retrieval-20261011T135303009Z-785ea7d4-dataset.jsonl'
```

## 验证与历史诊断排除

- 全量后端 **412 tests，0 failures/errors/skips**；本轮定向 **38 tests** 通过，覆盖指标、多标签 nDCG、冻结语料/作用域、标签复核、完整 runner、embedding HTTP、DashScope/Cohere/通用重排和配置绑定。
- Python 协议检查通过，包括实际 chunked POST 和 10 客户端共 200 次 embedding 请求；PowerShell 语法校验、248 条 dataset validate-only 和 diff whitespace 检查通过。
- 有效真实基线与 fake 回归均经独立脚本重新计算所有指标/分桶/百分位/均值/最大值，并核验 corpus hash、992 条质量 journal、50 条预热及 500 条正式样本。
- 正式基线采样期间没有运行另一轮 benchmark 或 Maven 测试。现有容器、应用数据、其他模块工作区改动继续保留；独立 helper/JVM 随脚本结束清理。

以下轮次完整保留，全部排除于达标判断，不拼接指标：

| Run suffix | 状态 / 原因 |
| --- | --- |
| `131020737Z-ac1925bd` | Native SDK bootstrap 长时间等待后中断；无完整质量/延迟 |
| `131616805Z-77c21d7e` | Native SDK 等待后完成索引，开始部分 Vector 质量请求，随后中断；原标记已按 journal 纠正为未完成四组 |
| `131856165Z-708130b3` | Fake 重排 helper 最初不支持客户端 chunked POST；修复后重新测量 |
| `132326267Z-b47ecfa6` | JDK HTTP/1.1 直连 bootstrap 请求超时 |
| `132556927Z-c3b9afe1` | 本地 ONNX 质量运行出现 3 次连续检索/重排失败；剩余问题显式 skipped，未压测 |
| `134836525Z-b002bad5` | Fake 中一次 embedding 请求失败；调整本地 HTTP/1.1/backlog、并发协议验证后重新完整测量 |
| `135027039Z-3670f160` | JDK HTTP/1.1 经代理 bootstrap 请求超时；正式基线随后使用已有完整索引，并逐条核验 |
| `140257249Z-a320ce55` | 调参后段被 DashScope Arrearage 拒绝；未压测 |

2026-09-27 的旧报告同样保留，不代表当前 248 条冻结数据集的四组完整实验。最终可以主张已交付可复核的评测实现和上述暂定实测结果；**不能主张 Recall@5 91%、P95 490ms 或已完成人工标签验收**。
