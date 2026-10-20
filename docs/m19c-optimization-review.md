# M19C 代码审阅与迭代建议

审阅日期：2026-10-13。范围：三个 Agent、共用检索与证据工具、计划执行与反思、M19C 判定和完整 100 任务运行记录。本轮只做代码审阅和离线诊断，未修改生产 Agent，也未重新调用模型。

结论：有明确的迭代空间。当前存在会话历史未传入 Plan、证据固定取前缀、ReAct 未预留收尾调用等实现问题，不能将结果简单归因于模型能力。修复这些问题、校准标注并重新验收是合理路径，但现有证据不足以保证达到 80%。

## 1. 基线与证据口径

完整实跑：`c21e2d4f-2ec8-4b2d-ab6f-5457be5e1337`，100 个任务全部尝试，共 121 个实际轮次。单轮总截止时间 120 秒，单次模型/HTTP 请求预算 30 秒，ReAct 最多 6 次模型轮次与 6 次工具调用，Plan 最多一次重规划。本次关闭 rerank。

| 模式 | 任务数 | 自动约束通过、待人工复核 | 失败 | 运行错误 | 自动约束通过率 | 最终 TaskSuccessRate |
|---|---:|---:|---:|---:|---:|---|
| Retrieval-First | 30 | 16 | 14 | 0 | 53.33% | N/A |
| ReAct | 35 | 12 | 23 | 0 | 34.29% | N/A |
| Plan-Execute-Reflect | 35 | 10 | 16 | 9 | 28.57% | N/A |
| 总体 | 100 | 38 | 53 | 9 | 38.00% | N/A |

100 个 case 的标注均待人工确认，38 个待审结果也尚未完成语义复核。38% 是自动约束通过率，是在当前固定标注及判定政策下的条件性成功率上界，不能当作已验证的真实任务成功率。

按任务去重的失败原因如下，类别可以重叠，不能直接相加。

| 失败原因 | 任务数 |
|---|---:|
| 引用不在标注允许的 chunk 范围 | 39 |
| ReAct 步骤/工具预算耗尽 | 9 |
| Plan 模型超时 | 9 |
| Plan 非法计划 | 2 |
| 可回答任务没有真实引用 | 3 |
| 必需工具未满足 | 3 |
| 任务状态不匹配 | 11 |
| 轮次未完成 | 2 |

39 个任务只触发了引用范围这一类自动失败；这不表示它们都答对或都存在标注错误。即使假设这 39 题全部修好，且现有 38 个待审结果全部语义正确，也只有 77 题。达到 80 题必须继续解决执行、检索与答案语义问题。

基线来源：[完整运行报告](D:/Project/ai-document-agent/reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4.json)。离线诊断：[脚本](D:/Project/ai-document-agent/reports/m19c-review-20261013/analyze.py)、[逐题结果](D:/Project/ai-document-agent/reports/m19c-review-20261013/diagnostic.json)。诊断使用冻结语料，核对其 SHA-256；当前构建的 23 个已记录 class 文件哈希也与基线一致。

## 2. 优先修复的代码问题

### [P1] Plan 已加载会话历史，但规划和答案生成没有使用它

位置：[PlanExecuteReflectAgent.java:84](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/PlanExecuteReflectAgent.java:84)、[Planner.java:29](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/Planner.java:29)、[Executor.java:230](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/Executor.java:230)。

规划器只接收当前 `question` 和上轮 gap ID，最终合成也只收到当前问题和证据。`context.memory()` 没有传入这两个阶段。`Planner` 又将返回计划的 goal 固定回原始当前问题，因此仅给规划 prompt 加历史，仍不能解决最终合成中的省略与指代。

实跑中 Plan 的 5 个多轮 case（087～091）无一通过自动约束。AGENT-089 第一轮已经正确描述简骑马、预计下雨后留宿，第二轮问题“她母亲为什么建议用这种出行方式？”却检索到莉迪亚婚事等无关片段并拒答。这是可复现的历史传递缺口；其余多轮失败还涉及超时与引用，不能全部归因于这一处。

建议：引入有长度上限的会话上下文/当前任务意图，在规划、检索和合成阶段一致使用；同时保留原始当前问题用于防止目标漂移。历史只用于消解人物、文档和事件，答案事实仍须来自本轮实际取得的证据。生产代码不得读取 benchmark 的 `requiredFacts` 或标注 chunk ID 来解决检索。

验收：至少覆盖两轮“简骑马 → 她母亲为什么”、三轮连续指代、英文 he/she/his/her，以及历史中的错误答案不会被当作证据。

### [P1] 搜索与上下文工具始终只返回 chunk 的前 1,200 字符

位置：[SearchKnowledgeBaseTool.java:81](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/react/SearchKnowledgeBaseTool.java:81)、[GetChunkContextTool.java:61](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/react/GetChunkContextTool.java:61)、[Executor.java:227](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/Executor.java:227)。

搜索使用固定前缀，FETCH_CONTEXT 仍使用同一截断函数，Plan 合成又截断一次。因此反复获取同一个 anchor 或增加 topK，也不一定能让模型看到已检索 chunk 后半部分的关键证据。

离线逐题核对发现 11 个任务至少有一个标注原句存在于已注册证据对应的完整 chunk，却不存在于实际可见文本：031、040、043、044、048、066、073、075、084、092、093。ReAct 5 个，Plan 6 个。某些任务仍有其他等价表述可供回答，所以这不是“11 题必然失败”或“修复后必增 11 分”的证明。

| 模式 | 可回答任务数 | 全部标注原句在实际可见证据中 | 全部标注原句在已注册证据对应完整 chunk 中 |
|---|---:|---:|---:|
| Retrieval-First | 25 | 15 | 15 |
| ReAct | 32 | 22 | 27 |
| Plan-Execute-Reflect | 32 | 11 | 17 |
| 总体 | 89 | 48 | 59 |

这个表比较每个 case 最后一个已记录轮次的证据，包括中止任务的最后尝试；是同文档原句的规范化字面匹配诊断，未评估语义、同义表述或事实蕴含，不是 Recall 或 TaskSuccessRate。

建议：搜索返回与查询相关的证据窗口；上下文工具支持在已授权 chunk 中按 offset/length 读取后续片段，保留总字符或 token 上限；合成阶段消费实际窗口，避免再次按前缀丢弃。`Executor` 的 `putIfAbsent` 也须支持同 chunk 证据扩展，防止缓存旧短片段。

兼容性要求：现有 [AgentBenchmarkCorpus.java:62](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkCorpus.java:62) 和第 88 行只认可原文前缀，同一引用的扩展也只接受前缀关系。改为窗口不能直接放宽为任意文本；应记录源 chunk 哈希、明确的原文跨度和模型实际收到的窗口，升级报告/审计格式并保留旧报告验证能力。

验收：关键事实在 1,200 字符之后仍可按工具预算读取；每个窗口能在冻结原文中精确验证；模型输入与审计快照一致；引用仍绑定当前知识库及真实来源。

### [P1] ReAct 在工具预算不足时直接丢弃已有证据，未预留最终合成

位置：[ReActAgent.java:114](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/react/ReActAgent.java:114)、[ReActAgent.java:132](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/react/ReActAgent.java:132)。

同一个 `maxSteps` 同时控制模型轮次和累计工具调用数。如果模型提出的一批工具调用超过剩余预算，直接返回 FAILED，而没有机会利用已有证据生成最终答案。9 个 STEP_LIMIT_EXCEEDED 任务实际只调用了模型 2～5 次，已执行 4～6 次工具，已经收集 9～20 个证据 chunk；并非都用完了 6 次模型轮次。

AGENT-053 第一轮已经取得含“always six o'clock”的证据，仍因工具预算结束失败，连第二轮都未执行。

建议：区分模型调用与工具调用预算；在原有 case 上限内预留一次禁止继续调用工具的最终合成。向模型提供剩余预算、已有证据摘要和明确的停止检索条件；对重复或近似查询做受控去重。批量调用超过剩余预算时返回明确的预算结果并进入收尾，保持供应商工具消息协议完整。证据不足仍应安全拒答，不得强行产生带引用的猜测。

验收：6 次工具调用以内仍能完成合成；有充分证据时不继续搜索到硬失败；不足时安全结束；超限批量调用不绕过预算；失败注入后的重试次数仍受控。

### [P1] 重排开关无法作用于 ReAct 和 Plan，检索改进未统一到三种模式

位置：[SearchKnowledgeBaseTool.java:54](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/react/SearchKnowledgeBaseTool.java:54)、[RetrievalFirstAgent.java:102](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/application/RetrievalFirstAgent.java:102)、[RagQueryRetrievalService.java:34](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/application/RagQueryRetrievalService.java:34)。

Agent 搜索工具将 `rerank` 写死为 `false`。即使开启全局 reranker，ReAct 和 Plan 的 70 个任务也会绕过它。该项不是本次关闭重排的基线失败原因，但会使下一轮“开启重排”的实验只部分生效。

已有普通 RAG 路径提供有界查询拆分、历史补全和多查询结果合并，Retrieval-First Agent 仍直接调用单次 gateway，ReAct/Plan 也没有共享任务感知的检索入口。跨文档任务缺少文档范围参数，模型只能在全知识库候选中反复查找，容易先填满无关证据。

建议：统一可配置的检索入口，明确区分候选数量与最终证据数量；共享有界多查询、历史消解、去重和可选重排；为跨文档子任务提供经当前知识库校验的文档范围。不能为了缩小范围允许跨知识库访问。

验收：重排开启/关闭在三种模式都能从 trace 验证；比较任务的两侧文档都能取得证据；多查询总耗时和调用数仍符合既定上限；先做同一模型、同一数据的对照，再决定是否需要模型调整。

## 3. 第二优先级：可靠性、反思与评估

### [P2] Plan 超时和非法计划需要分阶段治理

位置：[PlanExecuteReflectAgent.java:159](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/PlanExecuteReflectAgent.java:159)、[Planner.java:33](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/Planner.java:33)、[RagConfiguration.java:51](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/RagConfiguration.java:51)。

9 个 MODEL_TIMEOUT 全在 Plan。按已记录步骤定位，4 个发生在计划生成/修复阶段，5 个发生在合成阶段；还有 2 个 INVALID_PLAN。现有 trace 缺少每次模型调用的阶段耗时及超时来源，不能进一步断言是网络、服务端延迟或生成长度导致。

Plan JSON 靠自然语言约束，修复时只说“previous plan was invalid”，没有提供经过清洗的具体字段/依赖错误。应在当前供应商支持范围内使用结构化输出，对不支持的情况保留严格解析和一次错误反馈修复；建议生成紧凑计划，并分别记录 PLAN、REPAIR、SYNTHESIZE 的耗时、输出规模和安全错误码。规划和合成可采用不同参数预算，总截止时间保持有界。

仅提高 Agent 的 `modelTimeout` 不够：共用 HTTP read/request 本次也都是 30 秒。需要让模型、HTTP、单轮总预算一致，并评估提高超时造成的延迟和运行完整性影响；不能用无限重试或增大 case 上限掩盖失败。

### [P2] Reflector 将“有证据且引用合法”等同于目标已覆盖

位置：[Reflector.java:12](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/Reflector.java:12)。

当前只检查工具成功、证据列表非空、答案非空和引用合法性。检索到无关但非空的内容，也可能标记 `coverage_complete`。传回规划器的 gap 只是 step ID，缺少人物、文档或子问题的具体缺口。

6 个空证据注入都触发了一次受限重规划，证明机制能运行；它们不能证明语义缺口识别有效。

建议：从用户问题形成受控的子目标，记录每个子目标对应的实体、文档和证据覆盖；反思输出可审计的缺口摘要，允许一次针对性补检。保持 maxRevisions=1，不记录隐藏推理，不把 benchmark 标签当作线上覆盖标准。

验收：非空但不相关的证据也能触发一次修订；两侧比较有一侧缺证据时不会宣布覆盖完成；修订后仍不足时安全结束。

### [P2] 证据注册先到先得，满额后新检索可能完全无效

位置：[ToolExecutionContext.java:77](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/react/ToolExecutionContext.java:77)。

证据上限 20，达到后遇到第一个新 chunk 就 `break`，该批后续已有 chunk 的有效结果也会跳过。040、041、043、054 已到达 20 个注册证据。这证明满额场景确实出现，但不能单独证明这四个任务都是该缺陷导致。

建议：至少不要让一个新 chunk 阻断后续已注册结果；在注册前按相关度、子目标与文档分配有界名额，并提示剩余容量。后续如做替换或窗口扩展，必须保留已展示引用的稳定 ID、来源记录和真实模型可见证据，不能简单删除旧引用或无限增加容量。

### [P2] 引用标注需核对重叠 chunk，目标门禁也需明示分模式策略

位置：[RagAccuracyEvaluator.java:135](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/benchmark/RagAccuracyEvaluator.java:135)、[AgentBenchmarkRunner.java:341](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/benchmark/AgentBenchmarkRunner.java:341)。

自动判定要求所有引用的 chunk ID 属于预先标注范围。5 个范围失败候选（002、005、009、010、013）的每个范围外引用，实际可见文本中均包含同文档的某个必需事实原句。例如 AGENT-002 标注 chunk 251 的“四千英里”，被引用的重叠 chunk 250 也包含相同原句。它们值得独立复核；原句出现仍不等同于整段答案及全部轮次合格。

应核对并补全具有真实事实支持的等价证据范围，或使用经确认的证据跨度/事实支持关系；保留来源真实性、当前上下文、知识库范围和答案语义检查。不要将 39 个引用失败一律视为标注错，也不要删除范围检查来追求分数。

若修改标注，需要形成新的数据版本，先冻结，再完整运行；原版本报告保持原判定。旧结果可用于标注错误分析，不宜直接当作修改标注后的正式达标证据。人工复核应包含所有模式、失败题和全部对话轮次。

当前报告有 `targetByMode`，但 `-RequireTarget` 只看总体 `targetStatus`。例如 RF 成功 28、ReAct 成功 35、Plan 成功 17，总体刚好 80%，Plan 只有 48.57%，命令仍可通过。原需求要求报告分模式结果，没有明确规定各模式必须各自达到 80%；建议将分模式门槛作为显式防退化策略，并让命令行门禁与所选策略一致，避免自动验收掩盖明显失效的模式。

## 4. 建议迭代顺序与达标验收

1. **确认基线和标注**：独立复核现有标签与 5 个重叠引用候选；明确总体及分模式目标。保留旧 run、旧数据 SHA 和所有失败记录。标签调整独立版本化，不能与代码收益混算。
2. **修复上下文与证据可达性**：Plan 历史/意图贯穿规划和合成；工具可读取后续证据窗口；同步修改来源审计与报告格式。补充针对这两处缺陷的回归验证。
3. **修复执行收尾和检索入口**：在既定预算内预留 ReAct 合成；统一历史补全、查询拆分及可选重排；控制证据名额。先保持模型和数据不变，归因代码收益。
4. **处理 Plan 稳定性与语义反思**：记录阶段指标，再调整结构化输出、一次修复反馈和各阶段预算；使非空无关证据也能触发一次受限补检。
5. **正式完整验收**：使用冻结的至少 100 题完整运行，不删除难题。报告总体及三个模式的最终成功率、自动约束通过率、人工复核状态、错误率、延迟和调用成本。只有最终答案、事实、引用、工具约束、全部轮次和任务状态都满足，才计成功。

在现有 38 个待审任务最终全部正确这一乐观前提下，总体至少还需恢复 42 个任务才能达到 80；实际需求可能更高。若选择各模式均 ≥80% 的防退化策略，则对应至少 RF 24/30、ReAct 28/35、Plan 28/35，不能用一个强模式抵消另一个模式失效。

下一轮首先看代码缺陷是否消失、证据是否真实可达、错误率是否下降，再看人工确认后的 TaskSuccessRate。现阶段不应给任何单项修复承诺具体涨分，也不应提前宣称 80% 已有保证。
