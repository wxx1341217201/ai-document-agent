# M19C 交付记录（2026-10-02）

## 完成内容

- 固定 100 个任务：Retrieval-First 30、ReAct 35、Plan-Execute-Reflect 35。
- 19 个真实顺序多轮任务，完整运行会执行 126 个用户轮次；不会预置助手答案。
- 覆盖 23 个跨文档任务、29 个工具组合/多步骤任务、11 个拒答任务、4 个工具失败任务和 6 个一次重规划任务。场景标签存在重叠。
- `AgentBenchmarkRunner` 通过既有 `AgentRouter` 执行三种实际实现，任务间隔离历史与故障状态。
- 请求级证据观察、工具安全结果摘要、限定一次故障注入，普通请求使用 `AgentExecutionProbe.NO_OP`。
- 数据集、当前 KB 语料、实际上下文、引用、工具成功结果、预算、任务状态与重规划次数门控。
- 复用 M19B 的最终事实/引用人工评审规则，另核验每轮任务推进与安全错误处理。
- JSON/Markdown 总体、三模式、场景分桶；逐失败任务 step/tool/error 摘要；数据集副本、观察 journal、人工模板与修改检测。
- Validate、Live、Review 三个独立入口及 Windows 运行脚本。

## 本次新增/修改文件

### 新增

```text
benchmarks/agent/agent-tasks.jsonl
benchmarks/agent/build_dataset.py
benchmarks/agent/README.md
benchmarks/agent/DELIVERY.md
scripts/run-agent-benchmark.ps1
src/main/java/com/wxx/aidocumentagent/agent/AgentExecutionProbe.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkCorpus.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkDataset.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkEvaluator.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkReport.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkReportWriter.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkRunner.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkSessions.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkText.java
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkTrace.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkAgentFlowTest.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkCorpusTest.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkDatasetTest.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkEvaluatorTest.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkFixtures.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkReportTest.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkRunnerTest.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkSessionsTest.java
```

### 小范围修改

```text
src/main/java/com/wxx/aidocumentagent/agent/AgentExecutionContext.java
src/main/java/com/wxx/aidocumentagent/agent/react/ToolExecutionContext.java
src/main/java/com/wxx/aidocumentagent/agent/react/ToolRegistry.java
src/main/java/com/wxx/aidocumentagent/rag/application/RetrievalFirstAgent.java
src/test/java/com/wxx/aidocumentagent/rag/application/RetrievalFirstAgentTest.java
```

以上部分文件在本次开始时已是未跟踪文件；它们原有实现被保留，只增加所需的观察钩子和验证。
本次没有改写原有 application.yml、pom.xml、compose.yaml、.env 或之前的 Benchmark。工作区原有 ingestion、RAG、rerank、M18 等改动均保留。

## 数据库迁移

本模块无新增迁移，没有修改 V1..V13。Live 使用已有表；多轮任务临时创建空会话，只清理本 Runner 创建且 KB/标题仍一致的会话。

## 执行命令与真实结果

| 命令/检查 | 实际结果 |
|---|---|
| bundled Python 执行 `benchmarks/agent/build_dataset.py` | exit 0，生成 100 条，30/35/35 |
| `./mvnw.cmd -Dmaven.compiler.fork=true -DskipTests compile` | exit 0，编译 326 个源码文件，BUILD SUCCESS |
| `./mvnw.cmd -Dmaven.compiler.fork=true '-Dtest=AgentBenchmark*Test,RetrievalFirstAgentTest' test` | 最终 exit 0，35 tests、0 failures、0 errors、0 skipped |
| `./mvnw.cmd -Dmaven.compiler.fork=true test` | exit 0，306 tests、0 failures、0 errors、0 skipped；2026-10-02 22:43:45 +08:00 |
| `./mvnw.cmd -DskipTests compile` | exit 0，BUILD SUCCESS；2026-10-02 22:48:32 +08:00 |
| `./scripts/run-agent-benchmark.ps1 -Mode Validate` | exit 0，100 条结构/覆盖校验通过；无实际 Agent/数据库/模型调用 |
| `./scripts/run-agent-benchmark.ps1 -Mode Review -SourceReport reports/agent-20261002T144551268Z-c518f56a.json -Reviews reports/agent-20261002T144551268Z-c518f56a-review-template.jsonl` | exit 0，空模板未制造成功，TaskSuccessRate 仍为 null |
| `git diff --check` | exit 0；已有 LF/CRLF 提示不涉及回滚或覆盖 |
| `TcpClient.ConnectAsync('127.0.0.1', port)`，每端口最多等待 800ms | 3307、6333、6334、9200 均未监听 |

测试进程通过 `JDK_JAVA_OPTIONS=-Djava.io.tmpdir=D:\Project\ai-document-agent\target\sandbox-temp` 使用工作区内临时目录。
本机 Maven 缓存 `fontbox-3.0.8.jar` 的 zipfs 关闭阶段曾输出 AccessDeniedException；fork 编译最终 exit 0。未修改用户 Maven 缓存权限。
最初相关测试出现测试夹具 `Long` 参数类型错误与 Mockito 重设桩时的 NPE，均已修复并完成上述成功回归。

日志：`target/m19c-compile.log`、`target/m19c-tests.log`、`target/m19c-all-tests.log`、`target/m19c-final-compile.log`、`target/m19c-validate.log`、`target/m19c-review.log`。
测试中的模型/工具使用明确的 unit fixture，产物位于测试临时目录；它们不作为真实任务完成率报告。

## 当前可查看的报告

- `reports/agent-20261002T144551268Z-c518f56a.json`
- `reports/agent-20261002T144551268Z-c518f56a.md`
- `reports/agent-20261002T144551268Z-c518f56a-review-template.jsonl`（Validate 没有已执行任务，因此为 0 行）
- `reports/agent-31ab27a0-3ea3-44ec-9916-3d4e670f7da8-dataset.jsonl`（固定副本）
- `reports/agent-20261002T145348722Z-784df1bd.json/.md`（空模板离线 Review，仍为未执行）

数据集 SHA-256：`476b2eefbefb800d25e271de9c2c62e819b99521f233c85c789c4d8b7a86e3f0`。

| 模式 | 固定任务数 | 真实已执行 | TaskSuccessRate |
|---|---:|---:|---:|
| Retrieval-First | 30 | 0 | N/A |
| ReAct | 35 | 0 | N/A |
| Plan-Execute-Reflect | 35 | 0 | N/A |
| 总体 | 100 | 0 | N/A |

报告状态 `INCOMPLETE_RUN`，目标 `NOT_EVALUABLE`。没有验证或声称真实达到 89%。

## 关键设计决策

- 任务成功要求整个固定脚本执行完毕，最终答案、每轮引用、真实工具结果、预算和状态全部通过。
- 工具 audit COMPLETED 不足以代表检索有效；可回答任务需要非空证据，metadata 需要 found=true。
- 多轮上下文来自 Agent 的实际回答，人工复核还需确认每一轮是否正确推进任务。
- 受控故障只匹配当前任务/轮次/一次工具调用；Plan 缺口 profile 执行真实 search 后抑制一次证据。
- 错误、拒答、待审和未执行显式区分，完整分母固定。任务拒答属于显式任务成功类型，不修改 QA Accuracy 的口径。
- 全 KB READY 语料指纹、当前证据复核、固定数据集副本、规范化结果哈希与运行输入指纹保证评测可检查。
- 无 LLM judge；最终事实判定采用人工。只保存可见答案、文档证据、结构化步骤、工具安全摘要和稳定错误码。

## 尚存风险与下一模块前需确认

1. 基础服务当前不可用，尚未执行真实 Live；恢复 MySQL、Qdrant、ES 并确认 V1..V13、KB 2 语料与隔离索引。
2. 确认模型/Embedding/reranker 可用及实际部署/区域，再用 Live 完整运行 100 个任务并填写人工复核；当前仅为标注草稿。
3. Plan 当前未把会话历史传给 Planner，可能导致多轮指代失效；本模块保留其真实影响，产品修复应单独安排。
4. 当前语料只包含两本文学文档，任务形式有相关性；用于业务或简历结论前应补充业务任务并重新固定标签。
5. 强制终止可能保留本次创建的空会话；正常异常会执行有所有权和 KB 校验的清理，失败时报告保留 ID 与安全错误。
6. 确认 89% 结论来自哪一份完整、人工复核且语料稳定的 Live 报告，并同时检查三种模式分项。

---

## 2026-10-10：本轮 M19C 补充实施

### 完成内容

- 复用当前 100 条固定任务和既有 Runner、三种实际 Agent、成功判定、逐任务工具/步骤摘要及人工 Review 流程。
- 新增单次评测任务时间预算，默认 300 秒；按单调时钟判断，每轮使用 Agent 原 deadline 和剩余总预算中的较小值。
- 新增各模式样本上限，默认 0 表示全量。冒烟模式每种模式实际尝试 1 个任务，完整 100 条分母与 30/35/35 分项仍保留。
- 时间耗尽/样本限制分别记录 RUN_TIME_BUDGET_EXCEEDED / MODE_SAMPLE_LIMIT；已完成任务、原始 journal 和任务状态保留，部分执行不能判为达标。
- 评测 JVM 限制为初始堆 64MiB、最大堆 384MiB、2 个 active processors；使用已有服务，单并发。本次没有创建或重启 Docker 容器。
- 真实语料预检发现 7 个事实的源文标注问题。核对 KB 2 实际 MySQL 正文后，修订 18 条任务中的 19 处引用：4 个引用 chunk ID 偏移和 3 处引文文本错误。
- 修正记录保存原值、新值、KB、document ID、正文 SHA-256 及核对时间。任务问题、参考事实、可接受措辞、阈值、工具/轮次预算均通过原始快照对照验证，人工标注状态仍为 pending。
- 原始失败 Live 报告和 dataset 快照保留。修正仅作用于 M19C 任务生成，不改写 M19B 数据集。

### 本轮新增/修改文件

新增：

```text
benchmarks/agent/source-label-corrections.json
```

修改：

```text
benchmarks/agent/agent-tasks.jsonl
benchmarks/agent/build_dataset.py
benchmarks/agent/README.md
benchmarks/agent/DELIVERY.md
scripts/run-agent-benchmark.ps1
src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkRunner.java
src/test/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkRunnerTest.java
```

另新增本轮报告、数据集快照、观察 journal、人工模板及 target 下命令日志。
本轮开始时的 21 个 tracked 修改及已有未跟踪内容保留；没有 commit、push 或回滚。

### 数据库迁移

无新增迁移，未修改任何现有 Flyway 版本。语料核对查询显式限定 knowledge_base_id=2；评测沿用现有表。

### 执行命令及真实结果

| 命令 | 真实结果 |
|---|---|
| `.\mvnw.cmd -DskipTests compile` | exit 0，338 个源文件，BUILD SUCCESS；命令墙钟约 23 秒 |
| `.\mvnw.cmd test` | exit 0，360 tests / 0 failures / 0 errors / 0 skipped；约 54 秒，在源文标签修订前完成 |
| `.\scripts\run-agent-benchmark.ps1 -Mode Validate` | exit 0，固定 100 条结构/覆盖通过；不调用 Agent 或模型；约 17 秒 |
| `.\scripts\run-agent-benchmark.ps1 -Mode Live -MaxTasksPerMode 1 -MaxRunSeconds 90 -MaxConsecutiveErrors 1 -Deployment local-single-process-smoke-region-unspecified`（首次） | exit 0，約 25 秒；SOURCE_QUOTE_NOT_IN_LABELED_CHUNK 阻止运行，100 条 NOT_RUN，执行数 0 |
| KB 2 限定的 MySQL 只读查询 | 应用 MYSQL_USER 读取 41 个原标注 chunk 和 7 个相邻 chunk；定位 19 处不匹配引用。首次误用 root 账户得到 1045，改用 application.yml 指定的应用账户后读取成功；未改动账户权限 |
| bundled Python 执行 `benchmarks/agent/build_dataset.py` | exit 0，生成修正后的 100 条，分布 30/35/35；原始快照对照及所有源文引文核验通过 |
| `.\mvnw.cmd '-Dtest=AgentBenchmark*Test' test` | exit 0，标签修订后的 29 tests / 0 failures / 0 errors / 0 skipped；Maven 约 10 秒 |
| 上述同参数 Live（修正标签后） | exit 0，约 39 秒；实际执行 3 条，FAILED 3，NOT_RUN 97，corpusStable=true |
| `.\scripts\run-agent-benchmark.ps1 -Mode Review -SourceReport reports/agent-20261010T144858666Z-46c7380f.json -Reviews reports/agent-20261010T144858666Z-46c7380f-review-template.jsonl` | exit 0；空人工模板保持正式成功率 N/A，不会把失败任务算成成功，无模型调用 |
| PowerShell AST 解析、`git diff --check`、报告断言 | 通过；检查了全量分母、三模式各 1 个实测、3 个失败、97 个未执行、目标 NOT_EVALUABLE 和语料稳定 |

两次 Live 外层额外设置 240 秒进程上限，均自然退出，没有触发终止；所属测试进程已退出。
单次 Runner 的 `MaxRunSeconds` 约束语料预检后的任务执行预算；正在进行的数据库读取、Spring 启动、清理和报告写入仍使用各自的服务超时。脚本正常 exit 0 表示报告已生成，不表示 89% 达标。

### 当前真实报告及三模式结果

- 修正后的 Live：`reports/agent-20261010T144858666Z-46c7380f.json/.md`。
- 原始预检失败：`reports/agent-20261010T143845504Z-d79a93df.json/.md`。
- Validate：`reports/agent-20261010T143714664Z-47bbb89a.json/.md`（原始标签快照）。
- 空模板 Review：`reports/agent-20261010T145249490Z-352f4d9a.json/.md`。

| 模式 | 固定任务 | 实际执行 | 自动门控失败 | 未执行 | 正式成功率 |
|---|---:|---:|---:|---:|---|
| Retrieval-First | 30 | 1 | 1 | 29 | N/A |
| ReAct | 35 | 1 | 1 | 34 | N/A |
| Plan-Execute-Reflect | 35 | 1 | 1 | 34 | N/A |
| 总体 | 100 | 3 | 3 | 97 | N/A |

三个实测任务均有模型返回的最终可见答案，实际模型名为 deepseek-flash。自动失败原因分别为：

- AGENT-001：CITATION_OUTSIDE_ACCEPTABLE_RANGE:C1；Alice 的问题检索/回答了 Pride and Prejudice 的证据并拒答。
- AGENT-031：CITATION_OUTSIDE_ACCEPTABLE_RANGE:C6。
- AGENT-066：CITATION_OUTSIDE_ACCEPTABLE_RANGE:C3。

逐任务真实问题、答案、引用、step/tool/error 摘要见 Live JSON/Markdown。
READY 语料共 249 chunks，开始/结束指纹一致。任务引用范围及语义正确性仍须人工复核；不能将 3 条冒烟或自动门控结果等同于正式任务成功率。

### 关键设计决策

- 固定全部任务及分母，时间上限与小样本只影响执行范围；保留每一个未执行原因。
- 使用单调时钟限制整个任务运行，并复用 AgentExecutionContext 的 deadline，不新增通用 Agent 框架或改变生产 Agent 行为。
- 以持久正文修正输入标注并生成新 dataset SHA；原始报告不可覆盖，修正不用模型答案作依据。
- 自动引用、工具、知识库、任务状态检查与人工事实/多轮评审共同决定成功；空 Review 不能制造成功。

### 风险与未完成项

- 本轮为三模式冒烟，97 个任务未运行、100 个标签尚未人工核验；正式 TaskSuccessRate=N/A，89% 目标仍为 NOT_EVALUABLE。
- 实测暴露了检索/引用范围问题，保留真实失败；本轮没有修改生产检索、Agent 提示词或答案以改变分数。
- 多轮、工具失败和一次重规划已由固定任务及相关单元测试覆盖，本轮外部模型实测仅为三条单轮任务。
- 语料仍限于两本文学文档，任务形式相关；模型、供应商区域、评测配置和足够运行预算需要固定后才能开展正式比较。
- M19B 同源标签的对应源文问题需另行复核；本轮只修订 M19C。

### 下一模块前的确认事项

1. 人工核验固定任务、7 个源文修正、引用范围及失败任务，确认标注是否准确。
2. 确认正式 100 条模型评测的时间预算及供应商/区域；运行时保留所有失败，不自动长时间重跑。
3. 如需修复检索或 Plan 多轮能力，明确另行产品修复范围，再冻结版本开展完整评测。

本轮日志：`target/m19c-20261010-compile.log`、`target/m19c-20261010-tests.log`、`target/m19c-20261010-validate.log`、`target/m19c-20261010-corrected-tests.log`、`target/m19c-20261010-live.log`、`target/m19c-20261010-corrected-live.log`、`target/m19c-20261010-review.log`。


## 2026-10-13 M19C v2 实现更新（目标 80%）

本次按用户要求将验收阈值改为 `TaskSuccessRate >= 80%`，保留固定 100 任务的 30/35/35 分布、原始参考事实、全部失败任务与八类必需场景。下文历史 89% 与三条冒烟不作为本次达标证据。

### 实现与复现

- `AgentBenchmarkReport` / Writer 输出总体、三个模式、场景分桶、正式成功率、自动门控通过率、条件上下界和各模式 80% 对照。80/100 与 79/100 边界已测试；总体通过但 Plan 模式较弱的 fixture 仍展示分项 NOT_MET。
- 判定新增每轮真实历史消息数与 SHA-256 验证，拒绝替换历史、答案与结构化 trace 不一致。版本为 `m19c-human-final-v2` / `m19c-report-v2`；v1 输入可离线升级评估，历史原件不覆盖。
- 冻结完整 READY corpus，离线复核每条引用/实际上下文的文档、页码、正文前缀与原文 SHA-256。`AgentBenchmarkAudit` 重算全部门控、汇总、分桶与失败明细；仅忽略独立失败原因的排列顺序，值和重复次数仍须一致。Audit PASSED 不代表任务成功或人工复核完成。
- 保存 JSON / Markdown、逐任务 journal、空 review 模板、每轮复核材料、失败 JSONL、原始 dataset 与 corpus 快照，均不覆盖现有报告。
- 脚本增加 Preflight / Audit、代理、超时与重排选项，默认完整预算 3600s。最终启动强制 Qdrant VALIDATE_EXISTING、ES 不初始化 schema、关闭 Flyway / ingest / durable execution / WebSocket / Rabbit 消费。`-RequireTarget` 在生成证据后对未达标或未复核结果退出非零。
- 构建器现在使用独立 `source-qa-labels.jsonl` 种子，SHA-256 `20661d8bb07c17157c4979e6f5995380073e1252438d1f894ff2b0f9845b7fc1`，不受 M19B 后续标签修改影响。`--check` 已核对 100 条原固定任务一致；当前 dataset SHA-256 为 `10ad3a70b2e9ae3861e77ae162a6fa9b8c10fbb745a693d79505af900fcf39e2`。JSONL 以 Git 属性固定字节，不改任务答案或阈值。

### 已完成验证

最终后端全量 441 项测试通过，其中 M19C 37 项；构建器一致性、PowerShell AST 和模块空白检查通过。

最终只读预检：[报告](../../reports/m19c-20261013/validation/agent-20261012T192519984Z-3111ee1f.md)，[审计](../../reports/m19c-20261013/validation/agent-20261012T192519984Z-3111ee1f-audit-bd0655ec.json)。预检没有调用 Agent / 模型，100 条 NOT_RUN 均保留。

实际运行前确认 249 个 chunks 与仓库原文逐字一致（0 差异），仅含 Alice、Pride and Prejudice 及 dummy PDF / Word 样本。自动审批最初因外部模型载荷尚未确认拒绝 Live；完成这项来源核验后同一完整运行获准，未采用绕过方式。来源核验保存在 `reports/m19c-20261013/payload-provenance.json`。

首个受限 Maven 运行停在 JVM 动态附加；仅终止本轮 Maven / test / jcmd 进程后正常权限重跑通过。首次全量回归发现 Audit 对独立失败原因排序过严，修复后 441 项全部通过，旧错误日志保留。工作区其他模块和前端改动没有重置或删除。


### 本次完整实测与结论

2026-10-13 在现有 KB 2、真实 DeepSeek Chat / DashScope Beijing embedding / HTTPS 代理及既有 v3 索引上完整尝试 100/100 任务，实际 121 轮；Agent 执行合计约 1937 秒。关闭可选 rerank，温度 0，并发 1，每轮 deadline 120s、模型调用 30s，总预算 3600s，Plan 最多一次修订。语料前后稳定（249 chunks），失败与超时均保留。

[完整报告](../../reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4.md)、[JSON](../../reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4.json)、[独立审计](../../reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4-audit-6d67fdf5.json)、[逐轮复核材料](../../reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4-review-packet.md)、[失败明细](../../reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4-failures.jsonl)。

| 模式 | 固定任务/实际执行 | 自动门控通过 | 门控失败 | 调用异常 | 正式 TaskSuccessRate | 固定标签条件上界 |
|---|---:|---:|---:|---:|---|---:|
| Retrieval-First | 30/30 | 16 | 14 | 0 | N/A | 53.33% |
| ReAct | 35/35 | 12 | 23 | 0 | N/A | 34.29% |
| Plan-Execute-Reflect | 35/35 | 10 | 16 | 9 | N/A | 28.57% |
| 总体 | 100/100 | 38 | 53 | 9 | N/A | 38.00% |

自动门控通过的 38 条保持 PENDING_REVIEW；100 个标签和各轮语义尚未由真实人工复核。整体及分模式正式成功率均为 N/A，目标 NOT_EVALUABLE；按当前固定标签，条件成功率上界 38% 已低于 80%，本次结果不能支持达标声明。未用词组匹配、测试 fixture、空模板或修改标签制造成功。

- 4/4 ReAct FAIL 与 6/6 Plan EMPTY_EVIDENCE 注入实际发生，每次恰好一次；6/6 Plan 场景均出现 r0_reflect/coverage_gap 与两个 validated_plan（一次修订）。ReAct 故障任务 2/4、Plan 重规划任务 3/6 通过结构门控，仍需语义复核。
- 自动错误涉及引用范围、无有效引用、步骤上限、工具要求未满足和模型超时；9 个调用异常均在 Plan 模式。失败标签 / 证据支持范围及核心陈述需人工核实。现有 Planner 未接入会话历史的限制仍在报告中明确记录，本次没有修改生产 Agent 以抬高分数。
- 完整报告离线 Audit PASSED；空白 Review 回放仍为 N/A / NOT_EVALUABLE，完整分母与所有观察保持不变。RequireTarget 验收对未复核报告退出非零。
- 初次预检 `agent-20261012T190729327Z-3a5e937a` 生成于 v2 判定版本更新前，仅作历史预检记录；本次采用最终只读预检与完整 Live，不拼接历史冒烟或部分任务。

复现完整基线：

```powershell
.\scripts\run-agent-benchmark.ps1 -Mode Live -Rerank $false -UseHttpsProxy `
  -MaxRunSeconds 3600 -MaxConsecutiveErrors 10 -AgentDeadline 120s -ModelTimeout 30s `
  -ReportDirectory reports/m19c-20261013 `
  -Deployment 'M19C fixed100; DeepSeek chat; DashScope Beijing embedding; no rerank; readonly v3 indices'

.\scripts\run-agent-benchmark.ps1 -Mode Audit `
  -SourceReport reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4.json
```

## 2026-10-13 优化迭代复测

本页以上基线历史保持不变。最新后端优化、483 项回归测试、五次完整迭代及最终报告见 [迭代交付](../../docs/m19c-iteration-delivery.md)。

最终固定 100 题自动约束通过 56 条，44 条失败、运行错误 0；三模式分别 21/30（70.00%）、17/35（48.57%）、18/35（51.43%）。数据、249 个来源全文与政策不变，所有失败保留，独立 Audit PASSED。正式 TaskSuccessRate=N/A，未支持 80% 目标达标。

## 2026-10-19 引用优化后续实施

已落地三模式事实与引文绑定、来源/实际输入快照验证、缺口补检、文档头部读取、候选池回填、稳定来源登记表与活动窗口、有界格式修复及逐题诊断。502 项后端测试通过；四次完整 100 题实验保留，最终自动检查通过 66 条、34 失败、0 运行错误，三模式为 23/30、22/35、21/35。引用范围失败 39→17；最终完整报告独立 Audit PASSED，534 个应用 class 与 249 个 READY 来源稳定，数据及评分政策未改。

本轮目标仍未达标，正式 TaskSuccessRate=N/A，含 7 条原自动通过任务回退；实验协议默认关闭，benchmark 显式启用。完整证据、回退清单、参数对照、人工复核材料和未完成项见 [引用优化交付记录](../../docs/m19c-citation-iteration-delivery.md)。
