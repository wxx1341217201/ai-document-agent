# M19A Retrieval Benchmark

评测 `VectorIndex`、`KeywordIndex`、生产 `HybridRetriever` 与 `RerankingApplicationService` 的四组对照。完整计时路径是 **Query Embedding + Vector Search + BM25 Search + RRF Fusion + Cross-Encoder Rerank**。两路检索并行，计时采用实际墙钟耗时，排除 ChatModel 答案生成和 benchmark worker 排队。

## 数据与标注

主文件 `retrieval-dataset.jsonl` 共 **248** 条：234 可回答、14 无答案。保留原文学语料 200 条，并新增 48 条技术问题，覆盖精确词、编号、错误码、语义改写、中文同义表达、跨段落、跨文档、相似内容干扰和无答案。标签允许重叠。`build_dataset.py` 从固定来源锚点生成，绝不读取预测结果来修改 `expectedChunkIds`。

这些是 **assistant-curated、待人工复核的标签草稿**。200 条文学题共享 45 个主题锚点，不能当作 200 个独立主题。技术补充语料是基于项目代码编写的短操作手册，也不能代表生产流量。达标声明必须先完成人工标签复核。

本地 KB 2 的 249 个文学 chunks 从 MySQL 只读加载。`supplemental-corpus.jsonl` 的 10 个技术 chunks 使用显式 fixture ID 900001–900010，仅写入独立 benchmark 索引；它们不插入应用数据库。完整运行语料为 259 chunks。真实生产分片实验应单独重新分片、固定 chunk ID 并重标，不能只修改 `chunkSize`/`overlap` 配置。

`retrieval-dataset-literary-v2.jsonl` 保留原 200 条主文件，用于解释 2026-09-27 的旧报告及 M19B/M19C 参考标签；`retrieval-dataset-v1.jsonl` 继续保留更早版本。旧版不具备错误码与编号分桶，因此新的严格覆盖检查不接受它作为完整 M19A 数据集。

## 运行

在仓库根目录使用 Java 21；脚本优先使用 `JAVA_HOME`。继续从 `.env`/环境变量读取数据库、Elasticsearch、DashScope 和 Cohere 凭据，不把凭据写入报告或参数文件。

只验证标签结构、数量与分桶，不调用数据库或模型：

```powershell
.\scripts\run-retrieval-benchmark.ps1 -Mode Validate
```

使用真实 DashScope `text-embedding-v4` 和 `gte-rerank-v2`（北京）：

```powershell
.\scripts\run-retrieval-benchmark.ps1 -Mode DashScope -Bootstrap -QualityConcurrency 4 -UseHttpsProxy
```

`-Mode DashScope` 明确选择嵌套 `input`/`parameters` 的 [DashScope 文本排序协议](https://www.alibabacloud.com/help/en/model-studio/text-rerank-api)，把返回的零起始候选索引映射回 chunk ID，拒绝缺失、重复、越界结果。API key 由 Spring 在进程内解析环境占位符，参数文件中不写明文 key。该模式覆盖已有 Cohere reranker 配置。

使用同一真实 DashScope embedding 和本地真实多语言 Cross-Encoder：

```powershell
.\scripts\prepare-retrieval-local-model.ps1
.\scripts\run-retrieval-benchmark.ps1 -Mode Local -Bootstrap -QualityConcurrency 4 -UseHttpsProxy
```

`-UseHttpsProxy` 显式采用已有 `HTTPS_PROXY` 的 host/port，并写入部署口径；没有代理时省略。默认新索引为 `ai_document_chunks_benchmark_m19a_v3`、`document_chunks_benchmark_m19a_v3` 和相应 alias，不影响旧 M19A/M19B/M19C 索引。`-Bootstrap` 是显式写索引操作；已有完整索引时省略，运行只读取索引。

本机原 Spring AI SDK 的 HTTP/2 embedding 调用曾出现长时间等待。新的 benchmark 专用 `BenchmarkHttpEmbeddingModel` 使用 **JDK HTTP/1.1、20 秒请求超时、零重试、无结果缓存** 调用相同真实 endpoint/model；底层搜索仍复用生产 `QdrantVectorIndex`。该差异写入配置，测量不能被描述为未经改动的默认 SDK 环境。

本地 Cross-Encoder 使用 [官方多语言 mMARCO 模型](https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1)，固定 revision `1427fd652930e4ba29e8149678df786c240d8825`，官方 `onnx/model_quint8_avx2.onnx`。模型、tokenizer、ONNX Runtime 版本、文件 SHA-256、CPU、线程、batch、最大序列长度和服务并发写入报告。query 与每个候选 passage 成对进行真实推理；没有标签查表或结果缓存。ONNX 包和模型只保存在忽略的 `target/retrieval-benchmark`。helper 只监听 loopback，脚本结束时清理子进程。

真实外部 reranker（使用 `.env` 中已有 endpoint、model 和 provider）：

```powershell
.\scripts\run-retrieval-benchmark.ps1 -Mode Live -Bootstrap `
  -Provider 'Cohere hosted Rerank API' `
  -Region 'Exact serving region undisclosed by provider; record actual client routing' `
  -QualityConcurrency 1 -UseHttpsProxy
```

必须如实记录供应商和区域/路由，未知服务区域须明确标为未披露，不能猜测。DashScope 的 `dashscope.aliyuncs.com` 对应 [北京 endpoint](https://www.alibabacloud.com/help/en/model-studio/base-url)，改 endpoint 时同时设置 `-EmbeddingProvider`/`-EmbeddingRegion`。

当前 Cohere key 响应头为 `x-trial-endpoint-call-limit=10`、`x-endpoint-monthly-call-limit=1000`，与 [官方 trial 限流说明](https://docs.cohere.com/docs/rate-limits) 一致，不能用于并发 10 的 500 次稳定压测。不要把降级到 RRF 的结果算作 Cross-Encoder 成功；需有足够配额的真实 key 才能测量外部服务的完整路径。

固定延迟 fake 回归，调用真实本地 Qdrant/Elasticsearch，但模型为确定性 token hash/词法重排：

```powershell
.\scripts\run-retrieval-benchmark.ps1 -Mode Fake -Bootstrap -QualityConcurrency 4 -ModelPort 8099
```

Fake embedding 固定附加 5ms、fake rerank 附加 20ms；总延迟仍受实际索引、HTTP 和调度影响。它不是真实 embedding/Cross-Encoder，目标状态始终 `REGRESSION_ONLY`，即使数值超过阈值也不可作为简历性能数字。

## 指标与压测

- Recall@5 = 前五中至少命中一个标注 chunk 的可回答问题数 / 全部可回答问题数。
- MRR@5 使用前五中第一个相关 chunk 的倒数排名；报告明确截断深度。
- nDCG@5 使用 binary relevance，对最多五个标注 chunk 的理想 DCG 归一化。
- 无答案题必须 `answerable=false`、`expectedChunkIds=[]` 并有 `unanswerable` tag；不进入前三个指标的分母，单独报告空结果数。空结果不等于 ChatModel 正确拒答，本模块不评测答案生成。
- 四组使用同一冻结数据集/语料。错误保留为 miss；任一路降级、重排回退、全空索引或证据变化均禁止达标判定。返回 chunk 的 ID、知识库、文档和正文须与冻结语料一致。
- 每组输出总体和各 tag 的 Recall、MRR、nDCG，并报告 Cross-Encoder 相对 Vector/RRF 的差异，负差异即分桶退化。
- 最少 50 次顺序预热，然后最少 500 次正式请求。固定 worker 并发默认 10，查询序列采用固定 seed 190019，均来自可回答问题。计时在 worker 启动后、调用完整检索前开始。模型服务内部排队包含在延迟内。
- P50/P95/P99 采用 successful samples 的 nearest-rank，并输出 mean、max、errors/errorRate。失败耗时也保存，不能悄悄删除失败样本后声明达标。预热有错或正式请求有错时状态 invalid。

## 配置实验与复现

可以调整 `-VectorTopK`、`-KeywordTopK`、`-RrfK`、`-RerankCandidates`（兼容别名 `-RerankTopN`）、`-ModelMaxLength`、`-ModelThreads`、`-ModelParallelism`。最后输出固定 Top-5。每次改配置另写完整报告，保留低于 91% 或超过 490ms 的数据，不能覆盖或拼接达标结果。

```powershell
.\scripts\run-retrieval-benchmark.ps1 -Mode DashScope -QualityConcurrency 4 -UseHttpsProxy `
  -VectorTopK 40 -KeywordTopK 40 -RrfK 20 -RerankCandidates 50
```

使用已有运行的 `*-corpus.jsonl` 快照可脱离原 MySQL 内容复现标注语料（Spring 启动仍需要项目数据库 schema）：

```powershell
.\scripts\run-retrieval-benchmark.ps1 -Mode Local -Corpus 'reports/retrieval-实际runId-corpus.jsonl' `
  -Dataset 'reports/retrieval-实际runId-dataset.jsonl' -Bootstrap -UseHttpsProxy
```

## 报告与人工复核

每轮生成 `reports/retrieval-<UTC timestamp>-<unique suffix>.json` / `.md`，以及同前缀的 dataset/corpus 快照、`observations.jsonl` 和 `label-review-template.jsonl`。报告包含四组对照表、全部每题 Top-5、各 tag 指标、配置/模型/供应商/区域、正式耗时及错误；journal 每题/每请求即刻 flush。

人工逐题对照 dataset 问题与 corpus 原文填写模板，`expectedLabelsVerified` 初始 null。填写真实 `reviewer`、ISO `reviewedAt`、`expectedLabelsVerified=true` 和必要 notes 后，用 `-LabelReview` 提供复核文件重新测量。复核绑定 dataset SHA、corpus fingerprint 和逐题 SHA，未知/重复/过时/被否决的标签会被拒绝。未完成全部标签复核时仍输出实测数字，但目标是 `PROVISIONAL_PENDING_HUMAN_LABEL_REVIEW`。不自动代填人工身份。

运行独立审计，按冻结标签、Top-5 和原始纳秒记录重新计算全部指标、分桶、P50/P95/P99、均值、最大值和请求数量：

```powershell
python benchmarks/retrieval/audit.py 'reports/retrieval-实际runId.json' --output 'reports/retrieval-实际runId-audit.json'
```

当前实测结果、实验比较、历史诊断排除和验证记录见 [DELIVERY.md](DELIVERY.md)。91% 与 490ms 始终是阈值，不能预写为实测结果。
