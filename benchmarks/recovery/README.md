# M19D：Memory & Recovery Benchmark

## 执行

在仓库根目录使用 PowerShell 和 Java 21：

```powershell
# 离线检查固定随机计划，不连接服务、不注入故障。
.\scripts\run-recovery-benchmark.ps1 -Mode Validate

# Docker 引擎运行后，使用本轮复测的固定 seed 和并发执行 1000 次恢复样本。
.\scripts\run-recovery-benchmark.ps1 -Mode Live -Samples 1000 -Seed 20261010 -Parallelism 4

# 固定并发 8，每个实例有独立的容器、端口和 JVM。
.\scripts\run-recovery-benchmark.ps1 -Mode Live -Samples 1000 -Seed 20261002 -Parallelism 8
```

Live 自动创建唯一的 `m19d-<runId>` Compose 项目、MySQL 数据库、Redis 和独立子 JVM。默认端口为 `13307`、`16380`，只监听 loopback。不会调用主应用或重启主 compose 中的服务。端口被占用时拒绝执行，不复用占用端口的服务。

脚本优先使用 `JAVA_HOME/bin/java.exe`，与 Maven 的 Java 21 保持一致，避免 PATH 中旧版 Java 启动错误。MySQL 健康检查使用评测账号连接评测数据库执行 `SELECT 1`；JDBC 建连和 Hikari 获取连接超时为 10s，socket 超时为 15s；Flyway 启动连接最多重试三次，间隔 1s。初始创建数据库、执行 Flyway 和启动节点最多同时进行两组，正式故障注入仍按指定 `Parallelism` 并行，报告记录 `initialStartupConcurrency`。故障后的连接、启动与轮询等待计入恢复耗时；注入前的环境启动和准备过程保留诊断日志，不计入该恢复耗时。评测不修改持久租约或检查点。

口令通过进程环境变量注入；未提供时由 Runner 使用安全随机数生成，仅传入其创建的子进程，不写入配置、源码、参数文件或报告。需要自行管理评测环境口令时设置：

```powershell
$env:M19D_DB_PASSWORD = '<benchmark-only password>'
$env:M19D_MYSQL_ROOT_PASSWORD = '<benchmark-only root password>'
$env:M19D_REDIS_PASSWORD = '<benchmark-only redis password>'
$env:M19D_ACCESS_TOKEN = '<at least 32 characters>'
# 可选端口，范围 10000..60000，两个端口必须不同。
$env:M19D_MYSQL_PORT = '13307'
$env:M19D_REDIS_PORT = '16380'
```

主项目 `.env` 中的 API key 不参与本评测。子 JVM 加载 `application-recovery-benchmark.yml`，移除继承的 `SPRING_*`、`APP_*` 设置，并将数据库地址限定为本次环境。现有 Flyway V1～V13 只在新建的评测数据库上执行，本模块不新增或修改迁移。

`-KeepEnvironment` 会保留本次 Compose 容器，子 JVM 仍会停止；默认 `down` 移除本次容器，保留 MySQL volume，方便后续调查。清理时以报告 `environment.environments` 列出的各实例 `composeProject` 为准，不对主 compose 执行故障操作。原始命令和子 JVM 日志在 `target/recovery-benchmark/<environmentRunId>/`，Docker inspect 只记录状态、标签，避免输出环境变量口令。

## 环境和计量范围

Profile 为 **CONTROLLED_AGENT_REAL_STORAGE_PROCESS_FAULTS**：使用确定性、可见最终答案的评测 Agent，调用实际 ConversationMemoryService、事务持久化、生产 REST、ExecutionRunner/ExecutionStore/RecoveryScanner、Redis/MySQL 和 WebSocket。没有模型网络调用，不能将结果当作真实模型准确率或生产部署丢失率。两个知识库为本次运行独有，所有故障操作先核验 knowledgeBaseId、conversation 所有权或容器/进程所有权。

默认固定并发为 1，`-Parallelism` 可设置 1..8。并发实例使用独立的 Compose 项目、MySQL、Redis、口令和 JVM；端口依次为基础端口 + 实例编号，启动前验证全部端口范围不重叠。每个样本使用独立 conversation。样本包含已提交 USER/ASSISTANT 消息与引用摘要，100 个样本的 24 条历史超过 Redis 短期窗口 20 条，完整历史由分页 MySQL/REST 校验。缓存裁剪不算历史丢失。

当前 M17 使用 MySQL 持久任务和本地 ThreadPoolTaskExecutor，Agent 没有 RabbitMQ 消费者。Worker 故障实际终止承载 Worker 的子 JVM，其 REST 服务与 Worker 同进程。报告明确记录这种拓扑；重复提交同一 Idempotency-Key 必须返回原 executionId，PAUSED 任务通过生产 resume API 恢复。

M17 的中间步骤恢复会重放只读 Agent 工作；评测核验已提交步骤、工具摘要和重放后的确定性答案。最终结果检查点存在时必须直接完成 execution，Agent 调用次数不得增加。生产 durable execution 的最终答案保存在 `agent_execution.result_json`，当前该接口不向 conversation_message 追加消息；此前已完成的对话消息与 execution 结果分别核验。

当前评测配置 lease=5s、单次 Agent deadline=15s，但 M17 claim 实际取 `max(lease, deadline + 30s)`，所以**实际租约为 45s**。进程被终止后等待真实租约到期；没有修改数据库中的 lease 来缩短等待。子 JVM 使用 64MiB 初始堆、256MiB 最大堆、2 个 active processors；边界等待中的持久取消状态每 250ms 核验一次。1000 次包含数百次真实进程重启与租约等待，完整执行会耗时较长。生产默认 lease=2min 的数据需要另行实测，不能直接替换本 profile 的环境说明。早期诊断使用 deadline=5s、实际租约=35s；原始报告保留当时配置，不能混用不同环境的数据。

## 故障

故障均衡分配后按 seed 打乱；1000 次时每类 142 或 143 次。先保存不可覆盖的计划快照再执行：

| 故障 | 注入和证明 | 恢复核验 |
|---|---|---|
| REDIS_KEY_LOSS | 删除本次 conversation 的真实 Redis key；核验原来存在、现在不存在 | MySQL 回源与缓存重建；完整 REST 历史 |
| REDIS_TTL_EXPIRY | 对该 key 设置 50ms TTL，等待真实过期 | 同上 |
| REDIS_RESTART | 检查容器 module/run/project 标签后 restart；StartedAt 必须变化；Redis 不启用 AOF/RDB，核验缓存消失 | 实际重新连接与重建；持久消息不变 |
| APPLICATION_RESTART | 在结果已提交、complete 尚未执行的窗口杀死子 JVM；记录旧/新 PID | 租约到期后 resume，最终结果摘要不变，Agent 不再执行 |
| AGENT_STEP_INTERRUPTION | 确认已提交步骤和工具摘要，在边界等待的真实 Worker 线程上 interrupt | 受控 TOOL_FAILED、可重试 FAILED；一次 resume，结果一致 |
| WORKER_REDELIVERY | 在步骤已提交的窗口杀死承载 Worker 的子 JVM，重新启动消费者进程 | 检查点保留；相同请求重复投递仍是一个 execution；一次 resume，只读重放一致 |
| WEBSOCKET_RECONNECT | 真实连接、订阅后 abort，断线期间完成 execution，再建立新连接并重新订阅 | 记录前后 sequence 和是否观察到事件缺口；statusUrl 指向 REST 权威成功状态；消息保持 |

REST 连接超时 3s、请求超时 30s；只对 GET 的连接/超时和 502/503/504 重试，普通读取最多 3 次。状态轮询最多 600 次实际读取且受总超时约束，不叠加普通读取重试；不会自动重试写请求。恢复超时默认 90s，可用 `-RecoveryTimeoutSeconds` 调整（40..600）。resume 每个样本最多一次，重复 POST 是显式业务幂等测试。每个实例连续三次恢复错误后停止注入，其余计划仍保留 NOT_RUN。

故障前的瞬时网络准备错误最多准备三次，每次创建新的独立 conversation，保存 `preparationAttempts` 与 `preparationFailures`。实际注入故障后不重试该恢复样本；所有已发生的恢复尝试保留原判定并进入分母。

已完成的真实报告可以补执行尚未注入的 `NOT_RUN` 计划项：`-CompleteReport reports/recovery-<timestamp>-<runId>.json`。入口先核验冻结计划、原始汇总、组件与配置 SHA-256，只接受所有既有恢复尝试均可判定的 LIVE 报告；存在未验证故障或 RECOVERY_FAILED 时拒绝补执行。补执行使用新隔离环境，保留原报告及其 SHA-256、原始准备记录、补执行计划/journal，并生成新合并报告。编排入口自身允许升级，原/新指纹分别保存，持久化、恢复组件、判定器与配置必须一致。

Runner 中途终止且未生成完整报告时，journal 只证明已有最终记录的样本。没有记录的计划项可能包含中断中的注入，不能直接标成 NOT_RUN，也不满足 CompleteReport 条件。此时保留冻结计划和 journal，导出不可判定的中断报告；重新执行完整计划取得正式结论。

`RecoveryTimeoutSeconds` 控制单次 execution 恢复状态轮询。故障命令、节点启动和历史核验各有独立限时，逐样本总恢复耗时包含这些阶段，可能超过该轮询期限。

WebSocket 建连及订阅遇到瞬时连接错误、超时或 502/503/504 时最多尝试三次。鉴权失败、订阅拒绝、协议错误及作用域不一致立即失败；不会将失败连接伪装为已注入断线故障。每次成功的初始订阅及重连都记录 `initialSocketConnectionAttempts`、`reconnectedSocketConnectionAttempts`；耗时包含实际重试。异步异常记录安全的底层错误代码或异常类型，避免只留下 `ExecutionException`。

WebSocket ACK 中的 sequence 是读取时的事件水位，execution 成功事务提交后事件可能尚未发出；水位暂未增加不会被误判为会话丢失或恢复失败。真实订阅、abort、重新订阅与 REST/历史/Checkpoint 核验仍必须完成。

Docker 命令同样有限时：容器状态与标签查询各 90s，Redis restart 60s，重启后健康等待命令 90s；首次环境创建 180s，子 JVM 就绪等待 120s，清理 60s。命令超时记录为恢复错误，不伪造注入或恢复成功。

## 严格分类与公式

```text
SessionLossRate = SESSION_LOST / 实际恢复尝试数 × 100%
RecoverySuccessRate = RECOVERED / 实际恢复尝试数 × 100%
```

- **SESSION_LOST**：权威读取确认已提交用户/最终助手消息丢失或改变、会话知识库/顺序改变、已提交最终结果无法恢复，或恢复重放产生不一致结果。
- **RECOVERED**：已验证故障发生；完整持久消息与 scope/顺序一致；短期上下文和缓存重建；需要恢复的 execution、引用摘要、已完成步骤和幂等检查都通过。
- **RECOVERY_FAILED**：超时、连接失败、无法证明故障发生、缓存重建失败、执行未恢复、步骤检查失败或幂等检查失败。中间步骤缺失但重放结果一致单列恢复失败，不虚增严格 SESSION_LOST。
- **NOT_RUN**：计划校验、环境未就绪、准备失败或触发停止条件，未进行恢复尝试。

仅在 >=1000 个样本全部执行、故障已证实、权威状态可判定且实际存储环境已验证时给出 MET/NOT_MET。1000 次中 4 次真实丢失满足 `<0.5%`，5 次不满足。存在不确定的恢复失败或未执行样本时，SessionLossRate 和目标判定为 `N/A` / `NOT_EVALUABLE`，报告仍保留错误计数、观察到的丢失率下界和实际尝试的恢复成功率。7..999 个样本可以诊断，但不能给出模块目标达标结论。

恢复耗时从开始注入故障到权威状态、记忆和检查点核验结束，包含故障动作、服务重启、真实租约等待和轮询。JSON 同时保留每个样本的耗时、故障证据、前后消息摘要、检查点/step/tool/error 摘要；汇总 P50/P95/P99/max。WS 事件不重放且存在缺口，只要 REST 与持久状态恢复，不算会话丢失。不会保存原始模型输出或隐藏思维链。

## 结果文件

2026-10-11 正式复测完成：seed=20261010、并发 4，1000/1000 次恢复成功，SESSION_LOST=0、RECOVERY_FAILED=0、NOT_RUN=0；**SessionLossRate=0%，RecoverySuccessRate=100%，严格 `<0.5%` 目标 MET**，脚本退出 0。7 类真实故障各 142 或 143 次；独立核验确认 1000 个独立会话、7800 条已提交消息、100 个超出短期窗口的长历史样本和 571 个执行检查点样本。环境为上述受控 Agent 与真实存储/进程故障 profile。

查看 [正式报告](../../reports/recovery-20261011T074905084Z-28dd7a89.md)、[完整逐样本 JSON](../../reports/recovery-20261011T074905084Z-28dd7a89.json)、[独立核验结果](../../reports/recovery-28dd7a89-d71c-4c5f-9868-ada7492d8417-audit.json) 和 [交付与命令记录](DELIVERY.md)。正常清理后本轮所属容器及子 JVM 均为 0，保留 4 个 MySQL volume。当前全量 372 项测试通过。

2026-10-11 继续聊天时发现此前 Runner 已停止；该轮的 676 条成功记录与 324 条没有最终记录的计划项，保留在 [中断报告](../../reports/recovery-20261011T063511162Z-f47f7394-interrupted.md)。该中断轮不可判定，没有与正式复测拼接或改判为达标。

历史的 2026-10-02 复测按当时用户要求提前结束：记录 570 条，其中 569 条恢复成功、1 条 WebSocket 校验失败，确认丢失 0；430 条计划没有最终记录，该报告不能证明严格 `<0.5%` 达标。原始证据保留在 [历史中断报告](../../reports/recovery-20261002T183327435Z-9f4dac38-interrupted.md)；完整运行记录见 [交付与命令记录](DELIVERY.md)。

```text
reports/recovery-<runId>-plan.json             # 固定 seed 和全部故障计划
reports/recovery-<runId>-observations.jsonl    # 逐样本追加，可用于中断调查
reports/recovery-<timestamp>-<runId前8位>.json # 指标、按故障分桶、每个样本证据
reports/recovery-<timestamp>-<runId前8位>.md   # 可阅读汇总与逐样本状态
target/recovery-benchmark/<runId>/            # 子 JVM / 故障命令诊断日志
```

报告的 `environment.environments` 列出每个实例的 environmentRunId、composeProject、端口、运行配置和 runtimeDirectory；每个已准备样本的 faultEvidence 关联该 environmentRunId。报告同时保存计划、执行相关 class 和评测配置的 SHA-256。并发 journal 按完成顺序追加，最终 JSON/Markdown 按冻结计划顺序输出。默认清理只针对各实例创建的项目。

退出码：Validate 成功为 0；Live 正式达标且无运行/清理失败为 0，环境不可用、恢复失败、样本不足或未达标为 2。7 或 14 条真实诊断即使全部恢复成功，也因为不足 1000 条而返回 2，报告仍显示实际成功数。缺少 Docker/MySQL/Redis 时不制造 0% 丢失率或 100% 成功率。

用户主动停止时，journal 保留完整记录，中断导出使用 `m19d-interrupted-report-v1`，单列没有最终观察的计划样本。中断、未验证错误或样本不足都不能宣称达标。

## 实测过程中发现的修复

- 主配置与评测配置均明确选择 Hibernate 官方 `jackson3` JSON mapper，确保使用 `tools.jackson.databind.JsonNode` 的历史、步骤和结果检查点能够从 MySQL 正确读取。
- ConversationConfiguration 在 Bean 创建时通过 `ObjectProvider<StringRedisTemplate>` 选择 Redis，避免用户配置先于 Redis 自动配置解析时提前注册 NoOp 缓存。
- 最终结果检查点 resume 时保留已完成 `agent_run` 的 SUCCEEDED 状态，不将无需重跑的 Agent 标成 RUNNING。
