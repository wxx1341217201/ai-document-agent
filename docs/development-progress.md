# 开发进度

## 当前基线（M00）

- 技术栈：Java 21、Maven Wrapper 3.3.4（Maven 3.9.16）、Spring Boot 4.1.1、Spring AI 2.0.1。
- 本地基础设施：MySQL 8.4 映射至 `localhost:3307`，Redis 8 映射至 `localhost:6380`，应用端口为 `8080`。
- 数据库：Flyway 已启用，当前唯一迁移为 `V1__create_knowledge_base.sql`；新迁移必须以新的版本文件新增，不能修改 V1。
- 配置安全：本地机密放在未追踪的 `.env`；仓库提供 `.env.example` 模板。
- 连接约束：MySQL、Redis、RabbitMQ 和 Elasticsearch 已配置有限的连接或 socket 超时；RabbitMQ、Elasticsearch 的健康检查在当前默认 profile 禁用，因为它们不在本模块的 Compose 编排中。
- 测试：`test` profile 会隔离 MySQL、Redis、RabbitMQ、Elasticsearch 和 Qdrant，提供不依赖外部服务的最小 Spring 上下文启动测试。

## 从零启动

1. 在 Windows PowerShell 中复制环境变量模板：`Copy-Item .env.example .env`，然后只在 `.env` 中填写本地密码和 OpenAI API Key。
2. 启动当前基线依赖：`docker compose --env-file .env up -d mysql redis`。
3. 等待容器健康：`docker compose --env-file .env ps`。
4. 启动应用：`.\mvnw.cmd spring-boot:run`。
5. 验证基础设施健康：`Invoke-RestMethod http://localhost:8080/actuator/health`。预期响应中的 `db` 和 `redis` 均为 `UP`。

## 验证命令

```powershell
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd test
docker compose --env-file .env up -d mysql redis
Invoke-RestMethod http://localhost:8080/actuator/health
```

## 已知问题与待启动组件

- 当前 `compose.yaml` 有意只编排 MySQL 和 Redis。RabbitMQ、Qdrant、Elasticsearch 仍是后续模块需要的依赖，尚未启动，也没有业务代码；不得为了消除连接告警而从 `pom.xml` 删除它们。Qdrant 自动配置、以及 RabbitMQ 与 Elasticsearch 的 health contributor 会在相应组件接入时重新启用。
- 默认应用配置指向本地 MySQL 和 Redis。只有它们启动且 `.env` 已填写后，默认 profile 才能通过 Flyway 初始化并显示对应健康状态。
- 本工作目录当前没有 `.git` 元数据，因而本次无法运行 `git diff` 或用 Git 证明 `.env` 未被历史追踪；`.gitignore` 已包含 `.env`。初始化或恢复 Git 仓库后，应执行 `git ls-files --error-unmatch .env`，该命令应返回非零。

## 下一模块

开始知识库 CRUD 前，需要确认本地 Docker 守护进程可用，并以实际 MySQL 数据卷的 Flyway history 确认 V1 是否已执行。

## M19D 完成记录（2026-10-11）

- 已交付独立 RecoveryBenchmarkRunner、真实故障注入脚本、冻结随机计划、逐样本 journal、JSON/Markdown 报告及严格丢失率判定。
- 在受控 Agent 与真实 Redis/MySQL/进程故障环境中完成 1000 次恢复：全部成功，SESSION_LOST=0，SessionLossRate=0% <0.5%，RecoverySuccessRate=100%，目标 MET。
- 覆盖 Redis key 丢失、TTL 到期、Redis 重启、应用重启、步骤边界中断、Worker 终止后重新消费、WebSocket 断开重连。核验 7800 条持久消息、知识库与顺序，以及 571 个执行检查点样本。
- 当前全量 372 项测试通过；正式运行退出 0；本轮隔离容器与子 JVM 已清理，MySQL volume 和原始证据保留。历史中断结果没有用于拼接达标。
- [正式报告](../reports/recovery-20261011T074905084Z-28dd7a89.md)、[运行说明](../benchmarks/recovery/README.md)、[完整交付记录](../benchmarks/recovery/DELIVERY.md)。

以上 M00 部分保留初始项目阶段的历史记录；M19D 的实际配置、迁移范围与验证证据以本条及交付记录为准。

## FE08 完成记录（2026-10-11）

- 已实现 ProgressWebSocketClient、共享订阅/退订、指数退避重连、序列基线与缺口检测、重复/乱序过滤，以及订阅/重连/终态的 REST 重同步。
- useExecutionProgress / useDocumentProgress 已接入聊天执行回复、文档详情及列表。处理期间保留每 5 秒的权威状态核对，REST 确认终态后清理订阅；覆盖终态事件先于事务可见、REST 失败、切换/卸载和 StrictMode。
- 对齐实际 M18 JSON 协议：resource + knowledgeBaseId + executionId/documentId；文档事件使用摄取任务 UUID，documentId 位于 payload。Vite 服务端代理从启动进程环境读取可选的 PROGRESS_SUBSCRIPTION_TOKEN 设置握手 header，令牌不进入前端环境文件或客户端代码。
- 最终前端 13 个测试文件、87 项测试全部通过；浏览器 8 项全部通过；typecheck、lint、生产构建、格式和 diff 检查通过。新增 FE08 客户端 22 项、Hook 9 项和浏览器 3 项；未增加依赖。构建保留现有 bundle 体积提示。
- REST/WS 自动验收使用 Mock；未进行真实数据库/模型/鉴权后端的端到端联调。当前后端的 Execution 会话历史持久化及 M18 无回放/跨实例 fanout 限制仍适用。
- [FE08 交付说明、文件清单与人工验收](fe08-progress-websocket.md)。保留工作区已有 FE06/FE07、后端与 benchmark 改动。

## M19E 完成记录（2026-10-11）

- 交付 RabbitMQ A/B runner、PowerShell 脚本、独立 sync/async profiles、100 份固定 TXT，以及 batchSize=25/50/100 ×异步消费者 1/2/4/8 和 3 个同步基线组成的 15 组矩阵。
- 在同机、真实 RabbitMQ/MySQL、确定性本地 embedding/测试索引 profile 中完成 15 组有效实验，累计 4,610 次正式文档操作、507,640 chunks，错误为 0；预热和结束 drain 排除于稳定吞吐窗口。
- batchSize=50 的 submit_ack_latency 均值实测 16.08s →38.31ms；7.25s 基线未复现，等待对齐目标 NOT_MET。全矩阵峰值稳定完成吞吐 1.50→8.3167 文档/s，ThroughputGain=5.5444×，≥2.3× 目标 MET。单通道同步基线与测试 profile 均写入报告。
- 明确定义 ACK、队列等待、processing、READY E2E、完成吞吐；交付原始 JSON、逐文档/batch/stage/client/window/resource CSV/JSONL、完整 Markdown 报告及已检查的 PNG/SVG 图表。
- 修复首次摄取空 DELETE 的 MySQL 间隙锁、publisher-confirm 回写/消费领取竞态，以及同步评测立即领取的时间舍入边界和失败 ACK 计时。全量 390 项测试通过；正式 runner 退出 0，独立 audit PASSED；15 行环境清理完成，残留 M19E 容器和评测 JVM 为 0。
- [正式报告](../benchmarks/rabbitmq/results/run-20261011T091207Z-156e3c39/report.md)、[复现说明](../benchmarks/rabbitmq/README.md)、[交付与历史排除记录](../benchmarks/rabbitmq/DELIVERY.md)。旧轮次整体排除，未拼接有效行。

## FE09 完成记录（2026-10-11）

- Chat 新增桌面右侧 Agent panel 和窄屏 Drawer，支持查看当前及既有执行、Plan / Step Timeline、依赖与当前步骤、工具名/状态/耗时/安全数字摘要和引用 Drawer。
- 复用 FE08 的 Query 缓存与共享 WebSocket 订阅；步骤/工具事件触发 scoped REST 详情刷新。展示实时降级/重连、读取重试、retryable、暂停/失败/取消/完成；克制动画支持减少动画设置。
- 步骤与工具未知 JSON 均经安全投影，不显示模型隐藏思维链、原始参数/输出或任意 HTML；对齐实际 toolCalls、COMPLETED、AGENT 和 knowledgeBaseId 查询参数，没有编造 degraded 或完整 Plan/反思字段。
- 最终 typecheck、lint、生产构建及格式/diff 检查通过；15 个文件、101 项单元/集成测试全过，完整浏览器回归 10 项全过。新增 14 项 Vitest 与 2 项 Playwright；最终定向浏览器 2 项再次通过，桌面和 390px 截图已检查。
- 未新增依赖或修改后端；REST/WS 自动化均为 Mock，尚未真实模型/数据库/鉴权联调。同步模式缺少步骤、Execution 会话历史关联和模型 degraded 等字段的后端限制继续明确提示；FE10 列表/Resume/Cancel 未提前接入。
- [FE09 交付说明、文件清单、验证记录及人工验收](fe09-agent-visualization.md)。

## M19A 实现与实测记录（2026-10-11）

- 已交付 RetrievalBenchmarkRunner、248 条来源标注草稿（234 可回答 / 14 无答案）、10 个技术补充 chunks，以及四组对照、tag buckets、JSON/Markdown、冻结快照、逐请求 journal、独立 audit 与人工复核模板。
- 使用真实 DashScope text-embedding-v4（1024 维）与 gte-rerank-v2、北京 endpoint、本机 HTTPS 代理和 Docker 索引，完整四组 Recall@5 为 73.93% / 58.97% / 77.78% / 89.74%；MRR@5、nDCG@5 及错误码等分桶退化如实保留。
- 完成 50 次预热、500 次正式采样、并发 10，错误率 0%；P50=1579.43ms、P95=2930.99ms、P99=3952.26ms。计时包含 embedding/两路检索/RRF/重排，排除 ChatModel。91% 和 490ms 两项阈值均未达到。
- 调整到两路 TopK=40、RRF k=20、重排候选上限 30 后，DashScope 返回 Arrearage，25 个质量请求失败；保留为无效实验，未压测或拼接达标。用户选择先交付当前结果。Fake 固定延迟回归完成 50/500/10，P95=218.76ms，只作 REGRESSION_ONLY。
- 全量 412 项后端测试及 Python 并发协议检查通过；真实基线和 fake 独立 audit PASSED，两个真实索引各 259 chunks 全量内容/作用域核验 PASSED。benchmark 使用明确声明的 JDK HTTP/1.1 embedding 传输；标签 verified=0/248，尚未完成人工标签验收或生产性能达标。
- [有效实测报告](../reports/retrieval-20261011T135303009Z-785ea7d4.md)、[运行说明](../benchmarks/retrieval/README.md)、[完整交付、配置实验及历史排除](../benchmarks/retrieval/DELIVERY.md)。

## FE10–FE13 前端完成记录（2026-10-11）

- 实现任务中心、独立执行详情、状态机 Resume/Cancel、409 后 REST 重同步与稳定 URL 刷新恢复；下载/PDF/Chunk/Citation 文档跳转；Dashboard 最近数据与安全 System Health。
- 补齐 Unit/Component 测试及九步工作流、并发恢复、取消与刷新浏览器回归；另提供不拦截请求的真实后端验收配置。修复并发上传中逐次 observer 回调覆盖的问题。
- 所有 REST 位于 src/api，服务端状态使用 TanStack Query；仅展示结构化 Agent 状态与安全内容，无新增依赖、Secret 或后端修改，未开发 FE14。
- 最终 typecheck、lint、生产构建通过；30 个测试文件、156 项 Unit/Component 全过，12 项 Mock 浏览器回归全过，桌面/390px 截图已检查。真实后端 suite 仅执行了用例发现，没有伪报真实验收通过。
- 当前 Execution 列表、下载、Chunk、Citation Context、安全 Health 后端接口缺失；Context DTO 未定义，生产环境保留 quote，Mock 演示有明确标注。会话的 Execution 历史关联仍需后端持久化。
- 已执行的自动化使用 REST/WS Mock；真实后端当前未运行，真实 Citation/故障恢复联调尚未完成。完整命令结果、文件清单及人工验收见 [FE10–FE13 交付记录](fe10-fe13-delivery.md)。

## M19B 实现与实测记录（2026-10-12）

- 交付 200 条 QA（190 可回答 / 10 无答案）、可移植的 249 chunks 原文与来源摘要、RagAccuracyBenchmarkRunner，以及总体/五类题型、独立拒答准确率、JSONL 错题、逐题 journal 和人工复核材料。
- CORRECT 同时要求事实覆盖阈值、无关键冲突、核心陈述引用支撑和本次检索真实 chunk；使用人工最终判定 v2，不用词组匹配或未填写模板代替人工复核。运行前后冻结完整语料，离线重新验证证据/摘要/分母，审计拒绝删题、篡改答案和伪造准确率。
- 使用真实 DeepSeek Chat / DashScope embedding 与已有 Docker 索引，关闭可选重排的独立基线完成 200/200：96 条硬性引用检查判错、3 条模型超时、检索降级 0。最终 QA/拒答准确率仍为 N/A；当前固定草稿标签下 QA 条件上界 91/190=47.89%，不能支持 83% 达标。标签和 197 条实际生成答案尚待真实人工核实。
- 原 Cohere 配置因连续降级在 25 条后停止，175 条 NOT_RUN 保留正式分母；该无效运行独立保存，没有与完整基线拼接。评测强制只读索引校验、禁用后台任务及 Flyway；实际模型审计仍复用既有写入。
- 最终后端全量 424 项测试通过；完整基线、只读预检及离线空白模板回放的独立 audit 均 PASSED。回放保留全部答案/证据和指标，没有数据库或模型调用。数据集以 45 个文学/占位文档来源主题为主，尚不是生产分布或已通过人工标注验收的数据集。
- [完整基线报告](../reports/m19b-20261012/rag-20261011T211026767Z-be57bc72.md)、[复现说明](../benchmarks/rag/README.md)、[交付与人工复核入口](../benchmarks/rag/DELIVERY.md)。保留此前模块及前端的工作区改动。

## RAG 准确率第一轮迭代（2026-10-12）

- 分离两路候选数量和最终 TopK；增加最多三个原文子问题的有界并行检索、多轮用户主题补全、证据轮流合并与失败/取消处理，不额外调用 ChatModel。保持知识库边界、历史不可信处理和真实引用约束。
- 扩大字符预算并继续限制实际 token 窗口；仅去除已经完整进入上下文的重复正文，保留相邻独立事实。生成提示词要求逐项回答、分别比较和直接支撑引用。实际查询 trace 进入报告及复核摘要，旧摘要仍兼容。
- 固定原 200 条数据集、完整语料、模型和判定规则完成真实对照：完整标注证据可见 72/190 → 128/190，引用结构判错 96 → 60，截断遗漏 25 → 0。条件上界 47.89% → 64.21%；最终 QA/拒答准确率仍为 N/A，没有声明 83% 达标。
- 本轮执行错误 3 → 8（6 次超时、2 次不可用），检索降级 0 → 1，逐题含生成的 P95 19.20s → 24.89s；平均上下文输入字符约翻倍，稳定性/耗时仍需优化。跨文档仅 5/30 完整标注原文可见，仍是主要缺口。
- 最终全量 433 tests 全部通过；新实测及旧报告回放的独立 audit PASSED，同数据/语料/规则比较核验通过。未改标签、重新分片或重建索引；没有代填人工评审，保留其他模块的工作区改动。
- [本轮实测报告](../reports/rag-quality-iteration1/rag-20261012T080032515Z-c2dc3b0a.md)、[迭代说明、对照明细与复现命令](../benchmarks/rag/ITERATION-1.md)。


## M19C 实现与实测记录（2026-10-13）

- 按用户要求将目标从旧 89% 改为 ≥80%，固定 100 个任务、30/35/35 模式分布，覆盖多轮指代、跨文档、多工具、多步、拒答、工具失败与一次受限重规划；冻结独立标签种子，未改参考事实或筛除失败任务。
- v2 增加真实历史绑定、完整 corpus 快照 / 离线证据复核、总体 / 模式 / 场景统计、门控率与条件上下界、逐轮复核材料、失败 JSONL、只读预检 / 独立 Audit 和可选 RequireTarget 验收退出码。
- 真实 DeepSeek Chat / DashScope embedding / 无重排基线完整尝试 100/100、实际 121 轮；53 条门控失败、9 条模型超时、38 条待人工复核。三模式门控率 53.33% / 34.29% / 28.57%；正式 TaskSuccessRate=N/A，固定标签条件上界 38%，不能支持 80% 达标。标签与全轮语义仍待人工核实。
- 4/4 ReAct 工具失败与 6/6 Plan 证据缺口注入发生，六个 Plan 场景均实际出现缺口反思与一次新计划。READY corpus 249 chunks 前后稳定；完整报告 Audit PASSED，空模板回放没有制造成功。
- 最终后端全量 441 项测试（M19C 37 项）、构建器一致性、PowerShell AST 和空白检查通过。保留其他模块 / 前端已有改动、历史原件与真实失败，不改生产 Agent 提高分数。
- [完整实测报告](../reports/m19c-20261013/agent-20261012T194325054Z-07e1f5d4.md)、[复现与判定说明](../benchmarks/agent/README.md)、[完整交付记录](../benchmarks/agent/DELIVERY.md)。

## M19C Agent 优化迭代（2026-10-13）

- 修复 Plan 历史传递缺口，统一有界检索和用户主题补全；证据读取从 1,200 提至可配置 4,500 字符，另设 8,000 token 总预算与实际模型文本一致的证据快照。
- ReAct 分离模型/工具预算并预留收尾；每轮首次调用明确选择检索，旧会话引用清除；Plan 增加 JSON/输出预算、具体错误修复反馈与阶段耗时日志，显式证据不足仍只触发一次重规划。
- 真实原文前缀和来源哈希验证保留；同文档重叠仅在另一已选文本完整保留被删尾部时去重。重复正文、满额阻断和前缀扩展缓存问题均有回归覆盖。
- 483 项后端测试通过。增加供应商忽略工具选择时的应用层只读工具兜底、Plan SDK/EOF 有界暂时性异常重试、工具失败恢复、一次引用修复、明确上下文步骤保留及可开关的结构化证据覆盖检查；所有调用计入原预算，仍最多一次重规划。
- 固定 100 题、249 个语料 chunk 与基线一致，五次完整迭代自动约束通过 40/41/46/52/56 题。最终 100/100 题、126 轮完整执行，56 待复核、44 失败、运行错误 0，必需工具遗漏 0；三模式分别 70.00% / 48.57% / 51.43%，全部 10 个注入实际生效。仍未支持 ≥80% 达标。
- 最终独立 Audit PASSED；全部 521 个应用 class（含内部类）冻结且核对结束前未变。Cohere 重排已验证实际调用，未因单次烟雾结果推断其必然提升；最终复测关闭重排、开启结构化覆盖检查，后者生产默认关闭。正式 TaskSuccessRate=N/A，100 个标签及答案仍待人工复核。
- [本轮交付与完整结果](m19c-iteration-delivery.md)、[逐题对照分析脚本](../reports/m19c-iteration-20261013/compare.py)。

## FE14：Nginx 独立前端部署（2026-10-18）

- 采用方案 B：前端 Node 多阶段构建 + Nginx、Spring Boot Java 21 容器，与现有基础设施组成完整 Compose；文档原件使用持久卷，健康检查决定启动顺序，开发期 Vite proxy 保留。
- `/api` 保留路径/query/请求体；`/ws/progress` 配置 Upgrade、长连接、同源检查和服务器 secret 注入。令牌不进入 `VITE_*`、浏览器 JS/URL；生产禁用 MSW，并提供 SPA 深链接回退、缓存及静态缺失 404。
- 完整 Compose 已实际启动，7/7 服务 healthy；真实 Nginx 的 SPA/REST/WS 鉴权三项浏览器检查通过。初次业务工作流因 `EMBEDDING_AUTHENTICATION_FAILED` 失败，未把 Mock 回归当成真实 Citation/Agent 验收。
- typecheck/lint/build 通过，Vitest 30 文件 156 用例通过；其余验证、最终状态、依赖与文件清单及人工验收见 [FE14 交付记录](fe14-deployment.md)。
- 全量 Mock 浏览器回归 12/12 通过；Vite→Nginx→Spring 真实 WebSocket 握手曾因 Host 改写失败，保留 Host 后 1/1 通过，未关闭同源校验。最终全量 Vitest 再次 156/156 通过；本机 Embedding Key 尚缺，真实完整工作流仍未通过。
- Execution 列表、文档下载/Chunk/Context、安全 Health 接口及聊天 Execution 关联持久化仍等待后端补齐；独立任务详情 URL 的 REST 刷新恢复沿用已有实现。

## M19C 引用优化后续实施（2026-10-19）

- 共享结构化事实/原文绑定、服务端引用渲染、来源版本与实际可见快照验证已实现；三模式接入有界校验/修复，保留原模型、工具和一次重规划上限。
- 分离候选池和最终来源、从 READY 文档头部读取作者/标题、按缺口定向补检；ReAct/Plan 分离稳定来源与活动窗口并压缩旧工具正文，旧 ID 恢复时重新展示真实正文。
- 502 项后端测试通过；固定数据集 SHA-256 仍为 10ad3a70b2e9ae3861e77ae162a6fa9b8c10fbb745a693d79505af900fcf39e2。四次完整结果为 50/61/62/66 条自动通过，最终 100/100 题、126 轮、0 运行错误、10/10 注入实际生效，534 个应用 class 及 249 个 READY 来源稳定，独立 Audit PASSED。
- 最终 RF 23/30（76.67%）、ReAct 22/35（62.86%）、Plan 21/35（60.00%）；较 56 条基线新增通过 17、回退 7、净增 10，引用范围失败 39→17。34 条失败和全部回退保留，正式 TaskSuccessRate=N/A，80% 验收仍未完成。
- 新协议为显式实验开关，应用默认关闭，benchmark 默认显式开启；未代填人工复核、修改允许来源或绕过 RequireTarget。模型/候选池/重排诊断实验没有支持切换最终默认配置。
- [工程交付与完整结果](m19c-citation-iteration-delivery.md)、[最终完整报告](../reports/m19c-citation-20261018/delivery-full/agent-20261018T163502765Z-c2265a03.md)。