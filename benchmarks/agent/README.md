# M19C Agent Task Benchmark

当前验收目标：`TaskSuccessRate >= 80%`，固定 100 个任务时至少成功 80 个。JSON / Markdown 同时列出三种模式的成功率与 80% 对照，不能用总体达标隐藏某个模式的失败。历史 89% 记录保留在交付档案中，本次采用用户指定的 80%。

2026-10-19 引用优化迭代：502 项后端回归通过，固定 100 题自动检查通过 66 条（RF 23/30、ReAct 22/35、Plan 21/35），34 条失败、0 运行错误；引用范围失败由 39 降到 17。正式 TaskSuccessRate=N/A，仍未达到 80% 验收目标。[工程改动、完整报告及剩余事项](../../docs/m19c-citation-iteration-delivery.md)。

新结构化答案协议在应用中默认关闭，需显式设置 `AGENT_STRUCTURED_ANSWERS=true` 才启用；Plan 语义检查可设置 `AGENT_PLAN_VERIFY_EVIDENCE=true`。benchmark 的 Live/Preflight 默认显式启用二者，确保复现这轮实验；可用 `-StructuredAnswers $false -VerifyPlanEvidence $false` 做关闭协议的对照。不能把这组开关当作旧代码版本的精确回放。

## 固定任务集

`agent-tasks.jsonl` 固定 100 个任务：Retrieval-First 30、ReAct 35、Plan-Execute-Reflect 35。
每条保存真实用户 `turns`、最多 4 轮、参考答案、必需事实/覆盖阈值、引用范围、成功状态、工具成功次数、禁止行为和预算。
覆盖单文档、跨文档、多轮省略/指代、多工具、多步检索、拒答、工具失败和一次受限重规划。

标注复用 KB 2 的两本文学文档与 M19B 的源文引用，属于 `assistant_curated_pending_human_review` 草稿。
`source-qa-labels.jsonl` 冻结原始 200 条来源标签，构建器核对 SHA-256 后再应用源文修订，不随 M19B 后续迭代改变任务。
这些是 100 个固定任务，包含同一来源事实的不同任务形式，不能宣称为 100 个独立业务场景或已完成人工标注。
标签在执行前固定；Runner 不从模型答案生成参考答案。上线评测前需人工核对引用、阈值和工具约束是否合理。

2026-10-10 核对实际 KB 2 正文后，修正了 18 个任务中 19 处源文引用，涉及 7 个标注事实：4 个引用 chunk ID 偏移及 3 处引文文本。`source-label-corrections.json` 保存原值、修正值、来源正文 SHA-256 和核对时间；`build_dataset.py` 在生成 M19C 数据集时应用这些修正。任务 ID、用户轮次、参考事实、阈值及任务预算保持原值，标注仍待人工复核。首次失败报告 `reports/agent-20261010T143845504Z-d79a93df.json` 及原始数据集快照保留。

## 运行

```powershell
# 仅结构/覆盖校验，无数据库或外部模型调用
.\scripts\run-agent-benchmark.ps1 -Mode Validate

# 校验并冻结真实数据库语料，不调用 Agent / 模型
.\scripts\run-agent-benchmark.ps1 -Mode Preflight -ReportDirectory reports/m19c

# 每种模式实际执行 1 个任务，Agent 执行预算为 90 秒，完整 100 条分母仍保留
.\scripts\run-agent-benchmark.ps1 -Mode Live -MaxTasksPerMode 1 -MaxRunSeconds 90 -Deployment 'local-smoke'

# 基础服务启动、Flyway V1..V13 已应用、KB 2 及评测索引已准备后
.\scripts\run-agent-benchmark.ps1 -Mode Live -Deployment 'local-kb2-deepseek-cohere-region-填写真实区域'

# 关闭可选重排的独立完整基线；所有配置进入报告
.\scripts\run-agent-benchmark.ps1 -Mode Live -Rerank $false -UseHttpsProxy `
  -MaxRunSeconds 3600 -AgentDeadline 120s -ModelTimeout 30s -ReportDirectory reports/m19c

# 对真实报告完成人工复核后，离线重新评估；不重新调用模型
.\scripts\run-agent-benchmark.ps1 -Mode Review `
  -SourceReport reports/agent-<timestamp>.json `
  -Reviews reports/agent-<timestamp>-review-template.jsonl

# 离线重算全部门控与统计，核对任务/语料/答案快照，拒绝伪造汇总
.\scripts\run-agent-benchmark.ps1 -Mode Audit -SourceReport reports/agent-<timestamp>.json

# 验证构建结果与固定标签一致，不覆盖任务集
python benchmarks/agent/build_dataset.py --check
```

默认复用 M19A 的隔离 Qdrant collection `ai_document_chunks_benchmark_m19a_v3` 和 ES alias `document_chunks_benchmark_m19a_v3_alias`。
可通过 `-VectorCollection/-KeywordIndex/-KeywordAlias` 指向已准备的索引，Runner 不导入文档、不建评测索引、不执行 Flyway。
启动时强制 Qdrant `VALIDATE_EXISTING`、ES `initialize-schema=false`，索引缺失时失败，不自动创建。`-AgentDeadline` / `-ModelTimeout` 默认保留 40s / 20s；完整实测可显式选择更大预算，全部写入报告。
`.env` 通过现有 `spring.config.import` 加载。API key 继续使用环境变量，勿写入任务集、脚本、报告或 Git。
如 JVM 需要代理，用 `-UseHttpsProxy` 读取进程 `HTTPS_PROXY`；不要把密钥放到命令参数中。

Runner 默认关闭 ingest、Rabbit 消费、durable execution recovery 和 WebSocket；不启动 HTTP。
评测三种实际 `AgentRouter` 实现，顺序并发 1、Chat 温度 0、Plan 最大修订 1；各 Agent 的原有模型/工具超时和重试预算继续生效。
脚本将构建 classpath 并用 Java 参数文件避开 Windows 命令行长度限制。
评测 JVM 使用 64MiB 初始堆、384MiB 最大堆和 2 个 active processors，减少本地资源占用；不创建额外 Docker 容器。
每个模式连续 3 次基础设施异常后跳过该模式剩余任务，仍保留完整分母，并继续评测其他模式。可用 `-MaxConsecutiveErrors` 调整到 1..10。

`-MaxRunSeconds` 默认 3600，允许 1..3600，从语料预检开始限制任务执行预算。每轮使用原 Agent deadline 与剩余总预算中的较小值；预算耗尽后停止新增任务和后续轮次，已执行结果保留，其余任务标为 `RUN_TIME_BUDGET_EXCEEDED`。Spring 启动、数据库预检的正在进行的读取、会话清理、语料最终校验和报告写入仍使用原有服务超时，不属于硬性进程终止限时。

`-MaxTasksPerMode` 默认 0（执行全部固定任务）；大于 0 时按任务集顺序限制各模式的实际尝试数。其余任务标为 `MODE_SAMPLE_LIMIT`，报告保留 100 条及 30/35/35 分母，状态为 `INCOMPLETE_RUN`，不能以冒烟结果宣称 80% 达标。正式评测需要设置足够的时间预算；超时结果保持真实，不自动重跑或调整参考答案。

## 多轮与受控故障

- 多轮任务创建 Runner 所有的空会话，真实回答按顺序加入请求的 `AgentConversationContext`。
- 不预置助手参考答案、不调用生产 Redis 历史缓存；本模块测试 Agent 多轮能力，HTTP、缓存持久化与 worker 恢复另有现有测试。
- 只清理本次新建且 KB/标题一致的会话；报告保留会话 ID。进程被强制终止时空会话可能残留。
- 4 个 ReAct `FAIL` 场景：指定第 1 次合法 search 调用在触发服务前返回安全 `TOOL_FAILED`，要求后续有限处理并完成事实回答。
- 6 个 Plan `EMPTY_EVIDENCE` 场景：执行真实 search 后，仅在指定一次调用抑制其证据，制造缺口；要求实际出现 `r0_reflect/coverage_gap` 与一次新计划，之后给出有支撑的答案。
- 故障匹配任务、轮次、工具及调用序号，普通请求使用 NO_OP；HTTP 参数不能开启注入。
- 注入没有发生、未审计错误、工具空结果/metadata not found、重规划次数越界都不能通过。
- 当前 Plan 实现未将会话历史传给 Planner，指代任务可能失败。M19C 不修改 Agent 产品行为以提高分数。

## 成功判定与人工复核

自动门控检查完整轮数、每轮 `COMPLETED` 状态、任务工具/模型预算、真实工具结果、显式 KB、每轮引用对应本轮证据、最终引用范围、故障触发和重规划次数。
工具成功不仅要求 audit COMPLETED，还要求实际结果：可回答任务 search/context 非空，metadata `found=true`。
执行前校验全部标签引用及源文引用，执行中从同一 KB 的数据库复核实际证据正文/元数据，运行结束检查整个 READY 语料集指纹。
源文与上下文不同、跨库返回、缺失 chunk 或语料变化时不能给出达标结论。

最终答案复用 M19B 的事实/引用评审规则。人工模板所有布尔值默认 null；自动通过仍为 `PENDING_REVIEW`。
每个已执行任务都需要复核标签；错误任务也保留在分母中，并在模板中列出以便确认标签。
填写：

- `reviewer`、UTC `reviewedAt`、`referenceVerified`。
- 每个 `facts` 的 `covered`；为 true 时填写支撑该事实的最终答案 `citationIds`，必须命中该事实标注的 chunk。
- 可回答任务：`criticalConflictFree`、`coreClaimsSupported`。
- 无答案任务：`refusalCorrect`，确认实质拒答、没有凭空补出答案。
- `allTurnsAcceptable`：检查每轮实际回答是否正确推进任务、指代是否一致，而非只看最后一轮。
- `safeErrorHandling`：确认工具失败处理有限、对用户的说明合适，未暴露内部异常/错误信息。
- `notes` 可写人工判定依据；不要复制模型隐藏思维链。

`runId/datasetSha256/caseId/resultSha256` 绑定实际多轮输出。JSON 报告还固定原始数据集副本、配置与观察指纹。
标签、结果、运行环境被改动或删除失败任务时离线 Review 会拒绝；修订标签需要新一轮正式运行。
未填写模板行保持待审，不能自动记为成功。

```text
TaskSuccessRate = SUCCEEDED 任务数 / 固定全部任务数
```

拒答是任务集中的显式任务，与其他任务一起计入任务完成率。报告同时给出三个模式及各场景分桶，以及自动门控通过率与条件成功率上下界。自动门控通过率不是任务成功率，空人工模板不能产生 SUCCEEDED。
待审、标签未核验、未执行时最终率为 N/A，并另给已确认成功率上下界。
只有 LIVE、完整执行/复核、语料稳定，才能判断 80% 目标。失败不删样本、不改参考答案。
`-RequireTarget` 用于验收 / CI：先写出报告，再在目标未达到或未复核时退出非零。默认退出 0 只表示报告生成成功。单独 `Audit` 的 PASSED 只表示报告与冻结输入一致，不能代替任务成功或人工复核。

## 文件与安全边界

生成 `reports/agent-<UTC timestamp>-<id>.json/.md`、人工 review 模板、逐轮复核材料、失败 JSONL、不可变 dataset / corpus 副本及逐任务 observation journal。v2 报告可以随快照一起迁移；离线 Audit 核对全部统计、任务状态和证据正文，发现修改即退出非零。
失败任务明细包括所有用户问题、实际答案、引用、结构化 step、工具名称/安全参数摘要、耗时和稳定错误码。
只有最终可见答案和文档证据会保存，不保存中间模型消息、原始工具参数、供应商异常正文或隐藏思维链。
JSON/Markdown 使用 CREATE_NEW，已有报告不会被覆盖。
强制终止时 journal 保留已完成任务，但应完整重跑正式评测；不可通过裁剪分母合并出 80%。

`build_dataset.py` 可基于固定 M19B 标签重建任务草稿；它不运行 Agent、不产生评测分数。

## 2026-10-10 历史冒烟结果（不作为本次验收）

编译和全量 360 个测试通过；源文标注修订后，29 个 M19C 相关测试通过。
当时的真实冒烟报告：[Markdown](../../reports/agent-20261010T144858666Z-46c7380f.md)、[JSON](../../reports/agent-20261010T144858666Z-46c7380f.json)。
三种模式各执行 1 个任务，3 个任务均因引用范围不满足而自动判为 FAILED，97 个任务保持 NOT_RUN；正式 TaskSuccessRate 为 N/A，89% 尚未验证。
本次真实脚本运行约 39 秒，语料包含 249 个 READY chunks，开始/结束指纹一致。
未填写的人工模板经离线 Review 后仍为 N/A。完整命令、文件与风险记录见 [DELIVERY.md](DELIVERY.md)。


## 2026-10-13 完整 100 任务实测

[完整报告](../../reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4.md)，[交付与复现](DELIVERY.md)。后端 441 项测试通过（M19C 37 项），独立 Audit PASSED。

100/100 任务已尝试：53 条门控失败、9 条 Plan 模型超时、38 条待语义复核。三模式自动门控通过率依次为 53.33%、34.29%、28.57%；**这些不是正式任务成功率**。人工复核未完成，正式成功率 N/A / 目标 NOT_EVALUABLE，固定标签下总体条件上界为 38%，不能支持 ≥80% 达标。所有失败、完整分母与 249 chunks 稳定指纹均保留。

4/4 工具失败、6/6 证据缺口注入实际发生；六个 Plan 场景均只进行一次重规划。复核材料与空白模板随报告交付，没有代填人工判定。

## 2026-10-13 Agent 优化迭代

[迭代实现、验证与复现](../../docs/m19c-iteration-delivery.md)。保留原固定 100 题和判定政策；增加 Plan 全阶段历史传递、共享有界检索、4,500 字符证据读取、8,000 token 总预算、重叠与重复正文去重、ReAct 6/6 独立预算及收尾、每轮首次搜索、JSON 计划和一次具体验证反馈修复。

配置见 `.env.example`。DeepSeek 官方端点默认显式关闭思考模式，可用 `RAG_DISABLE_THINKING=false` 关闭该覆盖；Plan JSON 模式可用 `AGENT_PLAN_JSON_PLANNING=false` 关闭。`-Rerank` 同时控制三个 Agent，搜索 dispatch 摘要记录实际重排与降级状态。`-ModelTimeout` 同时作用于 Agent 与 HTTP 请求预算。人工复核完成前，自动约束通过率仍不能替代 TaskSuccessRate。

供应商忽略 ReAct 工具选择时，应用层仍会在本轮预算内补做真实检索；明确请求的元数据与相邻片段读取也必须经工具执行。Plan 最多重试一次暂时性模型失败，所有尝试计入调用预算；`AGENT_PLAN_TRANSIENT_RETRIES=0` 可关闭。实验选项 `-VerifyPlanEvidence $true` 启用结构化问题/答案/证据覆盖检查，发现缺口后仍最多重规划一次；它不读取参考事实，不是评测评分器。默认关闭，报告 `configuration.plan.verifyEvidence` 记录实际选项。

最终 483 项后端测试通过，固定 100 题完整复测自动约束通过 56 题（基线 38），三模式为 70.00% / 48.57% / 51.43%，运行错误 0、必需工具遗漏 0。完整报告与来源独立 Audit PASSED；数据集、249 个来源全文及评分政策一致，全部 521 个编译类保持冻结。**正式 TaskSuccessRate 仍为 N/A，未支持 ≥80% 达标**；所有失败及空白人工模板保留，详见上述交付说明。

可选 `-ChatModel deepseek-v4-pro` 只切换本次运行，用于同代码模型对照。最新同 12 题的 Flash / Pro 均自动约束通过 6 题，Pro 的本次耗时更长，未据此变更默认模型或声称一般性能结论。
