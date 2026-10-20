# M19B 交付记录（2026-10-12）

## 本轮实现与核验

评测实现已完成；83% 是验收阈值，最终 QA Accuracy 以绑定冻结证据的真实人工复核为依据。当前没有已完成的人工评审，不能声明实测达标。

后续第一轮代码优化及 200 条同口径实测见 [ITERATION-1.md](ITERATION-1.md)：完整标注证据可见数 72 → 128，引用结构判错 96 → 60；条件上界 47.89% → 64.21%，仍不是最终准确率。最终全量 433 项测试通过，本轮错误/耗时上升也已记录。

- 保留 200 条 QA、190 可回答 / 10 无答案及五类题型，补充可移植的 249 chunks 原始语料和 SHA-256 来源清单。构建器仅按原文短引文确定标签，包含有效的重叠 chunks，不使用预测结果重标。
- 修正初版 7 个原文不在标注 chunk 内的问题；其中 F57 改用实际描写回避追问的原句，F66 使用直接拒绝跳舞邀请的原句。参考答案不按模型表现改写。
- Runner 冻结完整知识库，包含可回答题没有标注的 chunks；运行前后检查正文、页码、索引、文档和知识库。离线复核再次验证快照和每项实际证据，支持配对快照迁移到另一目录。
- 引用核验失败保留实际可见答案和引用；执行错误/连续检索降级自动停止后续请求，未执行样本仍保留在正式分母。结构已判错的题不计入准确率上界。
- 新增独立 `audit.py` 和 `prepare_review.py`，导出 JSONL 错题及可读的人工复核材料；判定版本为 `m19b-human-final-v2`，初版 v1 报告保留为历史。
- 默认索引对齐当前 M19A v3，Qdrant 强制 `VALIDATE_EXISTING`，Elasticsearch 强制 `initialize-schema=false`，在评测前校验既有索引/别名；缺失时不补写索引或别名。预检和独立运行不执行数据库迁移，不启动后台摄取/Agent 任务。
- 全量后端 424 tests，0 failures/errors/skips；新增覆盖冻结语料、所有支持 chunk 的原文核验、未标注 chunk 变化、过期空模板、快照迁移、引用错误保留答案、持续降级保留分母、只读索引校验及未舍入 83% 边界。最终日志：`target/m19b-final-all-tests-20261012.log`。

实际语料仅包含《傲慢与偏见》《爱丽丝梦游仙境》、W3C Dummy PDF 和占位 Sample Word Document。真实执行使用 Java 21、已有 DeepSeek Chat / DashScope text-embedding-v4 / Cohere 配置、本机 HTTPS 代理和 Docker 索引。密钥没有进入问题或报告。Live 会复用既有模型审计写入，数据集/源文档/索引保持只读。

## 本轮真实执行

- 预检报告：[Markdown](../../reports/m19b-20261012/rag-20261011T204013094Z-a2017ed9.md) / [JSON](../../reports/m19b-20261012/rag-20261011T204013094Z-a2017ed9.json) / [独立审计 PASSED](../../reports/m19b-20261012/rag-20261011T204013094Z-a2017ed9-audit.json)。数据库 249 chunks 及 200 条标签核验通过，未调用模型；全部 200 条 `NOT_RUN`，准确率为 N/A。
- 带重排原配置：[Markdown](../../reports/m19b-20261012/rag-20261011T204504125Z-564170a4.md) / [JSON](../../reports/m19b-20261012/rag-20261011T204504125Z-564170a4.json) / [独立审计 PASSED](../../reports/m19b-20261012/rag-20261011T204504125Z-564170a4-audit.json) / [人工复核材料](../../reports/m19b-20261012/rag-20261011T204504125Z-564170a4-review-packet.md)。真实生成 25 条后因连续 3 次降级停止；总降级 6 条，未执行 175 条，结构判错 12 条，全部已执行题的标签/语义仍待人工复核。QA Accuracy=N/A，target=NOT_EVALUABLE，没有把回退结果当作有效 Cross-Encoder 测量。
- 关闭可选重排的完整基线：[Markdown](../../reports/m19b-20261012/rag-20261011T211026767Z-be57bc72.md) / [JSON](../../reports/m19b-20261012/rag-20261011T211026767Z-be57bc72.json) / [错题 JSONL](../../reports/m19b-20261012/rag-20261011T211026767Z-be57bc72-failures.jsonl) / [独立审计 PASSED](../../reports/m19b-20261012/rag-20261011T211026767Z-be57bc72-audit.json) / [人工复核材料](../../reports/m19b-20261012/rag-20261011T211026767Z-be57bc72-review-packet.md) / [待填写模板](../../reports/m19b-20261012/rag-20261011T211026767Z-be57bc72-review-template.jsonl)。检索超时 20s / 5s / 25s，真实执行 200/200，未执行 0、检索降级 0。96 条硬性引用检查判错，3 条 `RAG_CHAT_MODEL_TIMEOUT`；197 条实际成功生成的答案仍待人工标签/语义复核，人工已复核数为 0。QA Accuracy 和拒答准确率均为 N/A，状态 `INVALID_OR_INCOMPLETE_RUN`，目标 `NOT_EVALUABLE`。该实验不与带重排运行拼接。
- 最终只读预检：[报告](../../reports/m19b-20261012/validation/rag-20261011T211047930Z-e60d4c39.md) / [独立审计 PASSED](../../reports/m19b-20261012/validation/rag-20261011T211047930Z-e60d4c39-audit.json)。模式 `PREFLIGHT_ONLY`，249 chunks 稳定、200 条标签通过，没有模型调用；报告记录 `initialize-schema=false`。
- 离线复核回放：[报告](../../reports/m19b-20261012/validation/rag-20261011T211230314Z-6f0b8fad.md) / [独立审计 PASSED](../../reports/m19b-20261012/validation/rag-20261011T211230314Z-6f0b8fad-audit.json)。使用完整基线的空白人工模板，未调用数据库或模型；200 条问题/答案/证据/摘要、错题明细和所有总体/题型统计与源报告一致。没有将空白模板变成已完成人工评审。

完整基线按题型的可复核状态如下。上界假设所有尚未判错的可回答题最终均通过人工复核，基于当前冻结草稿标签；它不是实测准确率。

| 题型 | 样本数 | 硬性引用判错 | 调用错误 | QA Accuracy | 条件上界 |
|---|---:|---:|---:|---:|---:|
| 单文档直接事实 | 90 | 28 | 3 | N/A | 65.56% |
| 多片段综合 | 40 | 23 | 0 | N/A | 42.50% |
| 跨文档比较 | 30 | 25 | 0 | N/A | 16.67% |
| 多轮上下文 | 30 | 20 | 0 | N/A | 33.33% |
| 可回答题总体 | 190 | 96 | 3 | N/A | 47.89% |
| 无答案/应拒答 | 10 | — | 0 | 不进入 QA | 拒答准确率 N/A |

在当前固定标签和判定规则下，完整基线最多还有 91/190 条可成为 CORRECT，即上界 47.89%；当前证据不能支持 83% 达标。错题主要是引用超出标注可接受范围或缺少真实引用，具体问题、可见答案、引用与失败原因均在上述错题文件。语义正确性和标签是否恰当仍需真实人工逐题核实。数据集有 45 个来源主题及中英文改写，集中在两本文学作品和两个占位文档，不能解释为 200 个独立主题或生产分布。

主数据集与构建器已统一 LF，确保跨机器生成可重复。当前文件 SHA-256 为 `b33be7d7fd0ce405ad1c13c0c918e2c1ce6cecd17b43bc407e0c9bb77f7efc8f`；完整基线冻结快照为 `f91fa4fedf7eeb310aabcd410aa16b0ddbe1b06e5ea1b4fa77436bb70625d347`。两者逐样本 JSON 值完全一致，仅文件换行不同；历史运行仍按自己的原始快照摘要复核。`benchmarks/rag/.gitattributes` 固定源文件 LF，`reports/.gitattributes` 禁止改写运行快照字节。原始语料来源和摘要见 [source-corpus-metadata.json](source-corpus-metadata.json)。

本轮命令与校验说明见 [README.md](README.md)。本轮报告文件名采用 UTC 时间；上述运行在北京时间 2026-10-12。

## 2026-10-02 初版历史记录

以下保留初版交付过程。其中依赖服务未运行、版本 v1、测试数量、旧索引与尚未实测的结论只适用于当时，当前状态以上述本轮记录为准。

## 完成内容

- 实现 `RagAccuracyBenchmarkRunner`，复用现有 RAG 应用服务；支持真实生成、仅校验数据集、离线应用人工复核。
- 200 条 QA 草稿：190 可回答、10 无答案；单文档 90、多片段 40、跨文档 30、多轮 30。
- 固定参考答案、原子必需事实、覆盖阈值、可接受 chunk 范围和可见历史；核对本地书籍中的原文短引文。
- 自动核验知识库边界、当前上下文引用及数据库证据；人工决定事实覆盖、关键冲突、核心陈述引用支撑和拒答正确性。
- 总体/题型准确率、拒答准确率、错误/未执行明细、人工复核模板、不可覆盖的数据集快照和逐题执行记录。
- 保留本次开始前的工作区改动；未修改 pom、应用配置、compose、既有 Flyway 或后续模块。

## 新增文件

项目根目录为 `D:\Project\ai-document-agent`，以下均相对于该目录。

```text
benchmarks/rag/qa-dataset.jsonl
benchmarks/rag/build_dataset.py
benchmarks/rag/README.md
benchmarks/rag/DELIVERY.md
scripts/run-rag-benchmark.ps1
src/main/java/com/wxx/aidocumentagent/rag/application/RagEvidenceChunk.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/BenchmarkText.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyDataset.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyEvaluator.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyReport.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyReportWriter.java
src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyBenchmarkRunner.java
src/test/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyDatasetTest.java
src/test/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyEvaluatorTest.java
src/test/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyReportTest.java
src/test/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyBenchmarkRunnerTest.java
reports/rag-20261002T133701109Z-7a5e335d.json
reports/rag-20261002T133701109Z-7a5e335d.md
reports/rag-20261002T133701109Z-7a5e335d-review-template.jsonl
reports/rag-5367f233-4f30-49fc-b8f2-0444877f80d1-dataset.jsonl
```

校验模式没有生成答案，因此复核模板为空文件；这不是 200 条已完成复核。真实生成时每条已执行、无调用错误的答案都会生成待填写复核行。

## 修改文件

```text
src/main/java/com/wxx/aidocumentagent/rag/application/RagContext.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagContextBuilder.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagQueryExecution.java
src/main/java/com/wxx/aidocumentagent/rag/application/RagQueryApplicationService.java
src/test/java/com/wxx/aidocumentagent/rag/application/RagContextBuilderTest.java
src/test/java/com/wxx/aidocumentagent/rag/application/RagQueryApplicationServiceTest.java
```

只增加内部实际证据快照及提示词摘要；对外 HTTP DTO 保持现有结构，内部执行结果保留旧构造入口。快照保存模型实际收到的截断后正文，不能用完整数据库正文代替。正常问答/会话仍走原来的业务逻辑。

## 数据库迁移

无新增迁移，未执行 Flyway，未改变数据库结构。本次未调用真实 RAG，也未产生模型审计记录。未来 Live 模式复用既有 `rag_model_call_audit` 写入模型调用元数据。

## 执行命令与结果

| 命令/检查 | 真实结果 |
|---|---|
| 使用 Codex 已安装的 Python 运行 `benchmarks/rag/build_dataset.py` | 成功；200 条；45 个来源锚点及原子事实短引文在本地原文中找到 |
| `.\mvnw.cmd -DskipTests compile` | 最终 BUILD SUCCESS，退出 0；classes 已由 fork 编译器生成 |
| `.\mvnw.cmd '-Dmaven.compiler.fork=true' -DskipTests compile` | BUILD SUCCESS，退出 0；编译过程中出现本地 fontbox 缓存 jar 关闭时的沙箱访问告警 |
| `.\mvnw.cmd '-Dmaven.compiler.fork=true' '-Dtest=RagAccuracy*Test,RagContextBuilderTest,RagQueryApplicationServiceTest,RagConfigurationStartupTest' test` | 当时 35 项通过，0 失败/错误/跳过；之后补充 2 项快照/语料变化测试并纳入最终全量测试 |
| `.\mvnw.cmd '-Dmaven.compiler.fork=true' test` | 最终 280 项通过，0 失败、0 错误、0 跳过；日志 `target/m19b-tests.log` |
| `.\scripts\run-rag-benchmark.ps1 -Mode Validate` | 退出 0；生成上述 JSON/Markdown/数据集快照；全部 200 条 NOT_RUN，QA Accuracy=null，target=NOT_EVALUABLE |
| `docker ps` | Docker 引擎管道不存在；Docker 未运行，同时 Docker 客户端读取配置出现访问限制 |
| 本地 TCP 3307、6333、6334、9200 检查 | 均未监听；没有连接到 MySQL/Qdrant/Elasticsearch |
| `git diff --check` | 退出 0；只有已有 CRLF 转换提示 |

实际最低编译命令：

```powershell
.\mvnw.cmd -DskipTests compile
```

测试过程中仅对测试进程设置 `JDK_JAVA_OPTIONS=-Djava.io.tmpdir=D:\Project\ai-document-agent\target\sandbox-temp`，使临时文件位于可写工作区。初次增量编译发现报告 Writer 的一处括号语法错误，修正后编译及全部测试通过；数据集生成时发现两处不精确的引用文本，也已按本地原文修正。

上述测试使用单元测试 fixture 和现有受控 Spring 测试配置，不是外部供应商、真实数据库或真实 QA 准确率结果。没有把 fixture 答案或评审记录存入交付的实测报告。

## 关键设计

- 判定版本 `m19b-human-final-v1`；CORRECT 同时需要人工确认事实覆盖阈值、无关键冲突、核心陈述有引用支持，以及程序核实本次真实引用。
- 词组匹配只作初评；人工可以识别别名列表之外的正确改写，也必须识别匹配词组中的否定/冲突。未复核标签与答案不形成最终准确率。
- 普通 QA 分母固定为 190；拒答独立使用 10 条无答案题。调用错误仍保留在分母，连续 3 次错误停止后续请求。
- 每条人工复核绑定本次运行、数据集和输出摘要；报告删题、改答案、改证据、复用过期复核均被拒绝。
- 生成前后的标注源 chunk 指纹必须一致；发生语料变化、检索降级、错误或未执行时禁止达标声明。
- 不保存整个提示词、模型隐藏思维链或外部异常正文；只保存用户可见答案、证据、元数据、错误类型和提示词摘要。
- 不增加 LLM judge；最终依据为人工复核，避免引入额外模型服务和不可复现打分。

## 尚存风险与未完成项

- Docker 和依赖服务未运行，真实生成、当前数据库 chunk 标签核验和问答准确率尚未执行；没有 83% 实测结论。
- 数据集仍是 assistant-curated 草稿，必须人工确认参考事实、可接受引用和无答案标签。现有题材是两本文学作品，组合/改写题存在相关性，不能代表技术知识库所有问题。
- 模型、Embedding 和 Cohere 的当前额度、网络与实际区域未在本次验证；Live 首先依赖可用的数据库和索引。
- 编译器在受限环境关闭本地 fontbox 缓存 jar 时出现访问告警；fork 编译和最终最低编译命令均退出 0，最终测试全部通过。

## 下一模块开始前需要确认

1. 启动依赖，确认使用知识库 2 及默认 M19A 隔离索引，或提供已固定标注的新知识库数据集。
2. 明确实际生成模型、Cohere/Embedding 供应商、区域、网络环境与足够额度；Live 命令记录这些环境信息。
3. 安排人工核实标注并复核生成答案，再应用复核得到 QA Accuracy 和独立拒答准确率；低于 83% 时保存真实结果。

具体运行与复核命令见 `benchmarks/rag/README.md`。
