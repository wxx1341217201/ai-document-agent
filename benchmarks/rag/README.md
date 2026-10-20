# M19B RAG Accuracy Benchmark

本模块评测现有 `RagQueryApplicationService.queryForConversation`：混合检索、可选 Cohere 重排、数据库证据回填、上下文裁剪、答案生成与引用校验。多轮题使用数据集固定的用户可见历史，不创建会话，也不调用后续 Agent 工作流。

当前链路包含有界检索扩展、候选/最终 TopK 分离及保留独立事实的上下文去重。第一轮改动、对照配置和实测记录见 [ITERATION-1.md](ITERATION-1.md)。

## 数据集

`qa-dataset.jsonl` 固定在调用模型之前，绑定知识库 **2** 的真实 chunk。共 200 条：单文档 90、多片段 40、跨文档 30、多轮 30、无答案 10。每条保存 `referenceAnswer`、原子 `requiredFacts`、`factCoverageThreshold`、`acceptableChunkIds`、`answerable` 和历史。当前阈值全部为 1.0，复合答案会拆成多个必需事实。

`source-corpus.jsonl` 和 `source-corpus-metadata.json` 固定了 249 个原始 chunks 及来源、文档名和文件 SHA-256，可在没有本机上传目录的机器上重复运行 `python benchmarks/rag/build_dataset.py`。构建器从原文短引文确定每个事实的支持范围，纳入包含该证据的重叠 chunks；不用模型答案或检索预测修改标签。2026-10-12 修正了初版 7 个原文不在标注 chunk 内的问题，并修正两处引用措辞，参考答案保持原事实含义。

这些是 **assistant-curated、待人工复核的标注草稿**。`build_dataset.py` 中的 45 个来源锚点来自本地 Alice 和 Pride and Prejudice 原文；英中改写及组合题存在相关性，200 条不等于 200 个独立主题。题材也不代表技术知识库的生产分布。最终结果必须人工验证这些参考事实与无答案标签；不能将草稿或测试 fixture 的得分写成实测 83%。

Runner 在模型调用前检查：至少 200 条、五类题型、唯一 ID、同一知识库、标注 chunk 全部存在、每项 `sourceQuote` 位于该事实的每个支持 chunk 中、单文档/跨文档标签的文档数正确。任何标签或数据库不匹配都会停止生成并写诊断报告。

## 运行

从仓库根目录执行。使用 Java 21；脚本编译项目、构建 classpath，运行结束后关闭 Spring 上下文。已有 `.env` / 环境变量继续提供密钥、数据库口令和模型配置；脚本不写入或显示凭据。

只验证文件并生成 `NOT_RUN` 报告，不调用数据库或模型：

```powershell
.\scripts\run-rag-benchmark.ps1 -Mode Validate
```

真实生成：先启动本项目已配置的依赖，并保证数据库已迁移、知识库 2 的索引已建立。默认复用 M19A 的隔离索引。Qdrant 强制 `VALIDATE_EXISTING`，Elasticsearch 强制 `initialize-schema=false`，在评测前校验既有索引/别名；缺失时失败。脚本不创建、修改或清空索引，不自动运行 Flyway，不启动后台摄取或 Agent 任务。

数据库与索引预检、冻结完整语料，不调用检索模型或 ChatModel：

```powershell
.\scripts\run-rag-benchmark.ps1 -Mode Preflight -ReportDirectory reports/m19b
```

```powershell
.\scripts\run-rag-benchmark.ps1 -Mode Live -Deployment '填写实际 Chat/Embedding/Cohere 供应商、部署区域和网络环境'
```

可用参数：`-Dataset`、`-ReportDirectory`、`-TopK`（默认 8）、`-Rerank $false`、`-VectorTimeout`（默认 2s）、`-KeywordTimeout`（默认 2s）、`-TotalTimeout`（默认 3s）、`-VectorCollection`、`-KeywordIndex`、`-KeywordAlias`、`-UseHttpsProxy`。默认索引为 `ai_document_chunks_benchmark_m19a_v3` / `document_chunks_benchmark_m19a_v3` / `document_chunks_benchmark_m19a_v3_alias`。若使用其他知识库，先固定相应数据集及真实 chunk 标签。默认顺序调用（并发 1），生成温度固定 0.0，模型使用现有 `RAG_CHAT_MODEL` 配置。开启重排需要已有 `RERANK_ENABLED=true` 及有效的 `RERANK_PROVIDER` 配置。

第一轮优化另提供 `-CandidateTopK`（默认 40）、`-QueryExpansion`（默认 `$true`）、`-QueryExpansionTimeout`（默认 30s）、`-MaxContextCharacters`（默认 32000）及 `-MaxChunkCharacters`（默认 4500）。一次 QA 内最多三个扩展检索并行，QA 样本之间仍顺序调用。扩展只重用用户输入，不额外调用 ChatModel；实际查询保存在 `retrievalTrace`。

关闭可选重排并放宽检索超时的独立 QA 基线：

```powershell
.\scripts\run-rag-benchmark.ps1 -Mode Live -Rerank $false -VectorTimeout 20s -KeywordTimeout 5s -TotalTimeout 25s `
  -ReportDirectory reports/m19b -UseHttpsProxy -Deployment '填写实际环境；无重排 QA 基线'
```

不同配置必须保留为不同实验；不能把无重排基线或降级回退结果写成带 Cross-Encoder 的成功测量。

生成模式不会重新切片。报告保存 chunk size、overlap、RRF k、实际 topK、上下文预算、模型配置、每题实际模型名和提示词 SHA-256。引用快照包含实际进入模型的截断后正文及原始 chunk 内容 SHA-256；引用不会用完整数据库文本替代截断后上下文。模型连接/读取/总超时、Embedding 与 reranker 的有限重试继续复用现有配置。

本机网络若需要 JVM HTTPS 代理，可在本次进程的 `JDK_JAVA_OPTIONS` 中提供 `-Dhttps.proxyHost` / `-Dhttps.proxyPort`。使用已有真实网络环境；此模块不替换外部供应商为 fake。

每次运行先保存不可覆盖的数据集快照 `reports/rag-<runId>-dataset.jsonl` 和完整知识库快照 `rag-<runId>-corpus.jsonl`。人工复核使用快照；无答案标签也可对整个证据范围检查。正文、页码、chunk 索引、文档和知识库均进入语料指纹，包括可回答题没有标注的 chunks。运行前后指纹必须一致。

源数据统一 LF；运行快照按原始字节计算 SHA-256，`reports/.gitattributes` 禁用其换行转换。归档或迁移时保留快照原始字节及配对文件，不要重新格式化快照。

每题即刻写入 `reports/rag-<runId>-observations.jsonl`。连续 3 次执行错误或连续 3 次检索降级后停止后续调用，剩余题标记 `NOT_RUN`，所有样本仍保留在报告分母中。引用核验失败仍保存该次可见答案、引用和证据，便于诊断。部分检索/重排降级、语料在运行期间改变及调用错误均禁止达标声明。

## 判定与人工复核

自动检查仅用于硬性结构约束和初评建议：

1. 所有 `[C#]` 与程序返回的引用元数据一致。
2. 引用映射来自本题实际上下文，属于本题知识库，并与数据库原文、内容摘要和页码吻合。
3. 可回答题的引用在固定可接受 chunk 范围内。
4. 词组匹配给出 `preliminaryFactCoverage`；这不能判断语义冲突、否定表达或引用是否支持某项陈述，因此不直接产生 CORRECT。

人工复核填写同次生成的 `rag-<timestamp>-review-template.jsonl`，另存为自己的复核文件。未填写的行保持空白值，不要批量填 `true`。每行复核必须包含：

- `reviewer`：真实复核人；`reviewedAt`：ISO 8601 时间。
- `referenceVerified=true`：已核实该题参考答案、事实、可接受引用或无答案标签。错误标签需要修订数据集并重新生成，不能继续用旧摘要。
- 每个 `facts[]` 的 `covered`；覆盖的事实同时填写支撑它的 `citationIds`，这些引用必须出现在本次答案中并指向该事实允许的 chunk。
- 可回答题填写 `criticalConflictFree` 和 `coreClaimsSupported`，根据 **本次截断后证据** 判断整个答案。
- 无答案题填写 `refusalCorrect`：明确拒答且没有编造答案；不能仅根据某个拒答关键词判为正确。
- `notes` 可记录需纠正的事实、缺失证据或评审理由；只记录结论，不记录模型隐藏思维链。

复核行自动绑定 `runId`、`datasetSha256`、`caseId`、`resultSha256`，空白模板也检查绑定关系。答案、证据、历史、标签改变、语料快照改变或报告删题后，旧复核记录会被拒绝。应用复核时再次用冻结语料核验真实证据，**不重新调用检索、数据库或模型**。复制整个报告目录到另一台机器时，优先使用报告旁的配对快照。

可生成便于逐题阅读的 Markdown 材料：

```powershell
python benchmarks/rag/prepare_review.py reports/rag-实际时间戳.json
```

材料展示问题、可见历史、参考事实、实际答案、引用元数据和模型真正收到的 `contextText`，不会代填人工结论。然后应用复核：

```powershell
.\scripts\run-rag-benchmark.ps1 -Mode Review -SourceReport 'reports/rag-实际时间戳.json' -Reviews 'benchmarks/rag/my-human-review.jsonl'
```

复核会生成新的 JSON/Markdown，原报告保持不变。此版本不使用 LLM-as-judge，最终依据是人类对固定证据与实际答案的复核。后续若增加 judge，应另行固定模型、温度、提示词和版本并安排人工抽检。

判定版本为 `m19b-human-final-v2`。初版 v1 缺少完整语料快照，仅保留其历史报告；需要在 v2 冻结数据上重新生成和复核。

## 报告口径

`reports/rag-<UTC timestamp>-<unique suffix>.json` / `.md` 输出总体及五类分桶。JSON 的 `cases` 保存每题问题、参考事实、实际答案、真实引用、实际上下文、初评、人工复核和错误；`failureDetails` 和配对的 `-failures.jsonl` 汇总错题、执行错误、未执行原因。不存在真实失败题时，不虚构错题。

独立标准库审计重新计算总体/分桶准确率、拒答分母、阈值和错题明细，并核验原文、引用、快照摘要及 journal：

```powershell
python benchmarks/rag/audit.py reports/rag-实际时间戳.json
```

`auditStatus=PASSED` 只证明证据结构和算术通过，不代表人工语义复核完成，也不代表 83% 达标。真实实验与当前验收状态见 [DELIVERY.md](DELIVERY.md)。

`QA Accuracy = CORRECT 的可回答题数 / 全部可回答题数`。无答案题单独输出 `refusalAccuracy`，不会进入普通 QA 分母或分子。每项必需事实覆盖按人工确认且具有真实引用的事实数计算。

在人工标签/答案复核未完成或有未执行样本时，最终准确率是 `null` / `N/A`；同时提供已确认正确数、待复核数、准确率范围。硬性引用失败可先标记 INCORRECT，但该题标签仍需要人工核实。执行错误作为失败保留在分母，并使目标不可判定。只有完整、无执行错误、无检索降级、全部复核完成的真实运行才判定 `MET` / `NOT_MET`；真实低于 83% 时原样保存结果。

这不是延迟 benchmark：`elapsedMillis` 仅供每题诊断，包含答案生成和评测证据核验，不能代替 M19A 的 retrieval_latency/P95。
