# M19D 交付与验证记录

## 2026-10-11 正式交付结果

**M19D 已完成；1000 次真实故障恢复全部成功，SessionLossRate=0% <0.5%，RecoverySuccessRate=100%，目标 MET。** Runner 和 PowerShell 脚本均正常退出 0。本轮使用受控确定性 Agent，连接真实 MySQL/Redis，调用生产记忆、事务持久化、REST、WebSocket 与 M17 恢复组件；结果适用于该 profile。

- Run：`28dd7a89-d71c-4c5f-9868-ada7492d8417`；seed=20261010；并发 4，初始环境启动并发 2；Java 21.0.12。
- 计划 / 实际尝试 / 恢复成功 / SESSION_LOST / RECOVERY_FAILED / NOT_RUN：`1000 / 1000 / 1000 / 0 / 0 / 0`。
- 总恢复耗时 P50 / P95 / P99 / max：`923.399 / 48067.595 / 49372.565 / 50910.767 ms`。包含实际重启、45s 租约等待、轮询和权威状态核验。
- 1000 个独立 conversation；故障前后 7800 条已提交消息（3900 USER、3900 ASSISTANT）一致；100 个样本有 24 条完整历史，短期记忆保留最新 20 条。
- 571 个 execution 样本通过检查点、结果与业务幂等核验；最终结果检查点恢复时没有增加 Agent 调用次数。准备重试和 WebSocket 连接重试均为 0。
- 全量 372 项测试通过，失败/错误/跳过均为 0；本轮正式运行的代码与评测配置 SHA-256 已写入报告，并通过独立核验。
- 本轮所属容器和子 JVM 均已正常清理，4 个 MySQL volume 保留。没有创建 Git 提交或推送，也没有新增或修改数据库迁移。

| 故障 | 实际尝试 | 恢复成功 | SESSION_LOST | P95 ms |
|---|---:|---:|---:|---:|
| REDIS_KEY_LOSS | 143 | 143 | 0 | 251.201 |
| REDIS_TTL_EXPIRY | 143 | 143 | 0 | 379.304 |
| REDIS_RESTART | 143 | 143 | 0 | 7466.502 |
| APPLICATION_RESTART | 143 | 143 | 0 | 48551.559 |
| AGENT_STEP_INTERRUPTION | 143 | 143 | 0 | 1749.396 |
| WORKER_REDELIVERY | 143 | 143 | 0 | 49576.254 |
| WEBSOCKET_RECONNECT | 142 | 142 | 0 | 1213.468 |

正式报告：[Markdown](../../reports/recovery-20261011T074905084Z-28dd7a89.md) / [JSON](../../reports/recovery-20261011T074905084Z-28dd7a89.json)。冻结计划：[plan](../../reports/recovery-28dd7a89-d71c-4c5f-9868-ada7492d8417-plan.json)；原始逐样本追加记录：[journal](../../reports/recovery-28dd7a89-d71c-4c5f-9868-ada7492d8417-observations.jsonl)；独立审计：[audit](../../reports/recovery-28dd7a89-d71c-4c5f-9868-ada7492d8417-audit.json)。

独立核验重新计算冻结计划 hash、完整逐样本结果、指标和耗时分位数；确认报告与 journal 逐条一致、顺序与原计划一致、故障实际发生、完整消息/引用摘要/记忆及作用域一致、检查点和确定性答案一致、组件与配置 hash 一致。审计代码保留在 `target/m19d-audit-final.cjs`，清理证据在 `target/m19d-20261011-resumed-cleanup-verification.json`。

正式执行命令与日志：

```powershell
$env:JAVA_HOME = 'D:\Java\jdk-21' # 本机 Java 21 路径；其他环境使用其已有 JAVA_HOME。
.\scripts\run-recovery-benchmark.ps1 -Mode Live -Samples 1000 -Parallelism 4 -Seed 20261010
```

`target/m19d-20261011-resumed-live-1000.log` 保存完整运行日志，`target/m19d-20261011-final-tests.log` 保存 372 项全量测试结果。历史失败和中断轮仍按原结果保留；本轮是独立完成的完整计划，没有拼接中断数据。

| 最终检查 | 结果 |
|---|---|
| `./scripts/run-recovery-benchmark.ps1 -Mode Validate -Samples 1000 -Seed 20261010` | 退出 0；`reports/recovery-20261011T075218704Z-e18a9e27.json`；全部计划仅校验，恢复率为 null、目标 NOT_EVALUABLE，未伪造真实恢复结果；日志 `target/m19d-20261011-final-validation.log` |
| `git diff --check` | 退出 0；`target/m19d-20261011-final-diff-check.log` |
| 文档链接核验 | README、DELIVERY 和 development-progress 中 23 个本地文件链接均存在 |

## 完成内容

- 新增独立 `RecoveryBenchmarkRunner`、固定 seed 的 1000 条故障计划、故障注入脚本、JSON/Markdown 报告及逐样本追加日志。
- 覆盖 Redis key 删除、TTL 到期、Redis 重启、应用进程重启、步骤线程中断、Worker 终止后重复投递、真实 WebSocket 断开重连。
- 每个样本比较故障前后持久消息、知识库、消息顺序、引用摘要、短期记忆、执行结果、步骤检查点与业务幂等。
- 报告区分 `RECOVERED`、`SESSION_LOST`、`RECOVERY_FAILED`、`NOT_RUN`；对无法判定或未执行的样本保留真实错误，不生成达标结论。
- 实测发现并修复 JSON 持久化 mapper、Redis memory Bean 选择时机、最终结果检查点恢复后的步骤状态三处问题。

## 新增文件

生产源码前缀：`src/main/java/com/wxx/aidocumentagent/`。

| 路径 | 用途 |
|---|---|
| `recovery/benchmark/RecoveryBenchmarkRunner.java` | 隔离环境编排、故障注入、恢复检查、受控并行与报告 |
| `recovery/benchmark/RecoveryBenchmarkPlan.java` | 固定随机故障计划；每类均衡分配 |
| `recovery/benchmark/RecoveryBenchmarkObservation.java` | 权威状态、消息及结构化检查点摘要 |
| `recovery/benchmark/RecoveryBenchmarkEvaluator.java` | 严格丢失判定与恢复失败分类 |
| `recovery/benchmark/RecoveryBenchmarkReport.java` | 严格 `<0.5%` 计算、故障分桶与逐样本报告 |
| `recovery/benchmark/RecoveryBenchmarkClient.java` | 有超时及有限安全读重试的实际 HTTP/WebSocket 调用 |
| `recovery/benchmark/RecoveryFaultEnvironment.java` | 本次独立 Compose 项目、端口、口令与子 JVM 的所有权管理 |
| `recovery/benchmark/RecoveryBenchmarkNode.java` | 显式导入现有记忆、持久化、M17、REST 和 WebSocket 组件 |
| `recovery/benchmark/RecoveryFixtureAgent.java` | 使用真实历史生成确定性答案的受控 Agent |
| `recovery/benchmark/RecoveryFixtureService.java` | 评测数据与故障边界；先校验 knowledgeBaseId 和所有权 |
| `recovery/benchmark/RecoveryFixtureController.java` | 仅在独立评测节点启用的协议适配入口 |
| `agent/execution/ExecutionCheckpointObserver.java` | 最终结果提交后、执行完成前的可选观察点；默认 NO_OP |
| `src/main/resources/application-recovery-benchmark.yml` | 隔离数据库、Redis、执行和网络超时配置 |
| `benchmarks/recovery/compose.yaml` | 独立 MySQL/Redis；密码由环境变量注入 |
| `scripts/run-recovery-benchmark.ps1` | 编译、类路径生成与 Live/Validate 执行 |
| `benchmarks/recovery/README.md` | 运行方法、指标定义、故障范围和环境限制 |
| `benchmarks/recovery/DELIVERY.md` | 文件清单与真实验证结果 |

测试源码前缀：`src/test/java/com/wxx/aidocumentagent/`。

- `recovery/benchmark/RecoveryBenchmarkPlanTest.java`
- `recovery/benchmark/RecoveryBenchmarkEvaluatorTest.java`
- `recovery/benchmark/RecoveryBenchmarkReportTest.java`
- `recovery/benchmark/RecoveryBenchmarkClientTest.java`
- `recovery/benchmark/RecoveryBenchmarkCompletionTest.java`
- `recovery/benchmark/RecoveryFixtureServiceTest.java`
- `recovery/benchmark/RecoveryPersistenceJsonTest.java`
- `recovery/benchmark/RecoveryMemoryWiringTest.java`
- `recovery/benchmark/RecoveryFaultEnvironmentTest.java`
- `agent/execution/ExecutionRunnerCheckpointObserverTest.java`

## 修改现有文件

| 路径 | 修改 |
|---|---|
| `src/main/resources/application.yml` | 指定 Hibernate 官方 `jackson3` JSON mapper，兼容实体中的 Jackson 3 JsonNode |
| `src/main/java/com/wxx/aidocumentagent/conversation/ConversationConfiguration.java` | 在 Bean 创建时通过 ObjectProvider 解析 Redis，避免自动配置时序导致错误选择 NoOp |
| `src/main/java/com/wxx/aidocumentagent/agent/execution/application/ExecutionStore.java` | 已存在最终结果检查点时，claim 保留 `agent_run` 已完成状态 |
| `src/main/java/com/wxx/aidocumentagent/agent/execution/application/ExecutionRunner.java` | 在结果检查点事务提交后调用可选观察点；默认没有额外故障动作 |
| `src/test/java/com/wxx/aidocumentagent/agent/execution/application/ExecutionStoreClaimTest.java` | 验证已提交最终结果恢复时保持完成步骤状态 |

仓库中的 M19A/B/C、其他源码和配置已有改动均保留。没有创建 Git 提交或推送。

## 数据库迁移

**无新增迁移，无修改现有迁移。** 隔离 MySQL 使用当前仓库已有的 Flyway V1～V13。

## 命令与实际结果

以下路径均相对于仓库根目录。原始试验、冻结计划和 journal 都保留，各轮结果按日期分别记录。

### 2026-10-10～11 诊断与继续运行

| 命令或运行 | 真实结果与证据 |
|---|---|
| `./mvnw.cmd -Dmaven.compiler.fork=true test` | 当前源码全量 372 项测试通过，失败/错误/跳过均为 0；`target/m19d-20261011-final-tests.log` |
| `./scripts/run-recovery-benchmark.ps1 -Mode Validate -Samples 1000 -Seed 20261010` | 固定故障计划校验成功，退出 0；`reports/recovery-20261010T153736384Z-8ba07cb2.json`；Validate 不提供真实恢复结论 |
| 并发 8 的 `1dcb45b1` 诊断轮 | 中止时记录 326 条：230 次已尝试、229 次成功、1 次恢复失败、96 条已记录 NOT_RUN，另 674 条无最终记录。MySQL/Flyway 启动连接等待不足导致 NODE_START_FAILED；[诊断记录](../../reports/recovery-1dcb45b1-diagnostic.md)，清理证据 `target/m19d-20261010-diagnostic-cleanup.json` |
| 并发 8 的 `901dd4c2` 完整诊断轮 | 全部 1000 条有记录：999 次尝试、997 次成功、2 次恢复失败、1 条 NOT_RUN、确认丢失 0。SessionLossRate=null、NOT_EVALUABLE；原报告 [JSON](../../reports/recovery-20261010T171631927Z-901dd4c2.json) / [Markdown](../../reports/recovery-20261010T171631927Z-901dd4c2.md)，日志 `target/m19d-20261011-live-1000.log` |
| 并发 4 的 `f47f7394` 中断轮 | 继续聊天时 Runner 和子 JVM 均不存在，终止原因未确证。676 条完整记录全部 RECOVERED，另 324 条无最终记录；未记录项可能含中断中的注入，不能标成 NOT_RUN。SessionLossRate=null、NOT_EVALUABLE；[中断 JSON](../../reports/recovery-20261011T063511162Z-f47f7394-interrupted.json) / [Markdown](../../reports/recovery-20261011T063511162Z-f47f7394-interrupted.md)，日志 `target/m19d-20261011-live-1000-final.log` |
| 中断轮所有权核验与清理 | 核验 journal 中 4 个 environmentRunId 和容器 module/run/project 标签后，仅移除所属 8 个容器，保留 MySQL volume、冻结计划及 journal；`target/m19d-20261011-interrupted-cleanup.json` |

`901dd4c2` 中的错误分别是故障前 REST 准备超时、Redis 重启路径 Docker 查询超时及 Worker 恢复路径 REST 超时。两个已尝试错误没有被删除或改判成功，该报告不能用于 CompleteReport 拼接达标；后续采用更长的有限 REST/所有权查询等待和并发 4，重新进行完整评测。

当前全量测试包含 7 项补执行/准备重试保护测试，以及 WebSocket 瞬时连接重试、上限、鉴权与作用域拒绝、底层异步异常记录的验证。中断轮的 676 条原始记录还通过独立核验，确认消息及引用摘要、记忆窗口、知识库、顺序、检查点、实际重启证据和确定性结果一致；它们仍仅作为中断证据保留。

### 2026-10-02 历史验证

当时用户选择提前结束复测并交付已有结果；该中断结果不作为后续完整复测的达标证据。

| 命令 | 真实结果与日志 |
|---|---|
| `docker version --format '{{.Server.Version}}'` | Docker 引擎实际可访问，版本 29.7.2 |
| `docker compose ... config --quiet` | 隔离 Compose 配置解析成功，退出 0；没有实际注入故障 |
| `./scripts/run-recovery-benchmark.ps1 -Mode Validate` | 校验冻结的 1000 条计划，退出 0；全部 NOT_RUN，不宣称恢复率 |
| `./scripts/run-recovery-benchmark.ps1 -Mode Live -Samples 7` | 修复实际存储问题后 7/7 RECOVERED；不足 1000，Java 返回 2，脚本报错；报告 `reports/recovery-20261002T164552684Z-14110bf7.json` |
| `./scripts/run-recovery-benchmark.ps1 -Mode Live -Samples 14 -Parallelism 2` | 14/14 RECOVERED；不足 1000，Java 返回 2，脚本报错；报告 `reports/recovery-20261002T170322150Z-8498def9.json` |
| `./mvnw.cmd -Dmaven.compiler.fork=true test` | 修正存储配置及并行端口验证后的 353 项测试通过，失败/错误/跳过均为 0；`target/m19d-all-tests-final-353.log` |
| `./scripts/run-recovery-benchmark.ps1 -Mode Live -Samples 1000 -Parallelism 8 -Seed 20261002` 初轮 | 1000 条全部记录，实际尝试 982、RECOVERED 966、RECOVERY_FAILED 16、NOT_RUN 18、SESSION_LOST 0；RecoverySuccessRate=98.370672%，SessionLossRate=null，NOT_EVALUABLE；Java 返回 2，脚本失败退出；`reports/recovery-20261002T175657794Z-cc6e8a75.json` 与 `target/m19d-live-1000.log` |
| `./mvnw.cmd -DskipTests compile` | 修正版编译成功，退出 0；338 个主源码文件，Java release 21；`target/m19d-final-compile.log` |
| `./mvnw.cmd test` | 修正版全量 356 项测试通过，失败/错误/跳过均为 0，退出 0；`target/m19d-final-tests.log` |
| `./scripts/run-recovery-benchmark.ps1 -Mode Live -Samples 1000 -Parallelism 8 -Seed 20261002` 修正版 | 按用户选择停止：570 条完整记录，RECOVERED 569、RECOVERY_FAILED 1、确认 SESSION_LOST 0；430 条计划没有最终记录。已记录尝试的 RecoverySuccessRate=99.824561%，严格 SessionLossRate=null，NOT_EVALUABLE。Java 被主动终止，退出 -1，脚本退出 1；这不是测试失败。计划 `reports/recovery-9f4dac38-ce32-4e22-bad4-33b0c5e09f5c-plan.json`，日志 `target/m19d-live-1000-verified.log` |
| `git diff --check` | 退出 0；`target/m19d-diff-check.log` |
| 核验本轮参数文件、父进程树后 Stop-Process | 仅停止本次 Runner、Java launcher/JVM 与子节点；停止证据 `target/m19d-user-stop.json` |
| 核验 module/run/project 标签后 docker rm --force | 移除本次 12 个容器，保留 MySQL volume；证据 `target/m19d-user-stop-cleanup.json`；后续 `docker ps -a --filter label=benchmark.module=M19D` 为空 |
| 从冻结计划和完整 journal 导出中断报告 | JSON/Markdown 写入成功；核验 570 个唯一 case、7 类故障、570+430=1000；未记录样本不制造结果 |

### 2026-10-02 历史查看入口

- 可读报告：[提前结束的复测报告](../../reports/recovery-20261002T183327435Z-9f4dac38-interrupted.md)。
- 每条样本及停止/清理证据：[复测 JSON](../../reports/recovery-20261002T183327435Z-9f4dac38-interrupted.json)。
- 保留的初轮：[初轮 Markdown](../../reports/recovery-20261002T175657794Z-cc6e8a75.md)。

中断报告使用独立的 `m19d-interrupted-report-v1` schema，直接保留正常 Runner 写下的完整 case；不是正常完成 1000 条的 Runner 报告。未记录的计划可能包括尚未开始、环境失败或中断时仍在进行的尝试，不能全部说成未尝试。

早期诊断中的 JSON mapper、缓存未启用及 Windows inspect 参数错误对应原始日志和报告也保留；后续成功数据不覆盖先前失败数据。

## 关键设计决策

- 使用受控确定性 Agent 配合真实 MySQL/Redis、生产持久化和记忆服务、实际子 JVM 终止、实际线程中断与 WebSocket。没有外部模型调用结果。
- 所有故障资源归本次独立环境所有，先核验作用域与资源所有权；不操作主 Compose 的服务。口令只进入子进程环境，不进入 Git、源码、报告和参数文件。
- 最终结果检查点恢复不得重新调用 Agent；中间步骤使用仓库当前 M17 的只读重放策略，并校验结果一致及重复请求幂等。
- 以 MySQL/REST/Checkpoint 为权威状态，缓存丢失或 WebSocket 事件丢失本身不计 SESSION_LOST。
- 恢复失败或未执行样本会阻止严格丢失率的可判定结论。1000 个可判定样本中 4 个真丢失达标，5 个不达标。
- 正式复测前调整评测节点的截止时间和资源配额，并为安全 GET 增加最多三次重试；写请求无自动重试，resume 每个样本最多一次。
- 初轮证明 5s 受控 Agent 截止时间和并行运行资源配置可能使注入准备或核验失败；修正版记录实际 45s 租约及 256MiB 子 JVM 堆上限，不通过修改数据库租约加速。
- WebSocket 完成事件在成功事务提交后发出，ACK 水位可暂未增加。复测保留实际断开重连及权威状态检查，事件缺口只作为证据记录，不把该时序差异误判为会话恢复失败。
- Java 启动优先使用 JAVA_HOME；MySQL 健康检查以评测账号实际执行 SELECT 1，初始数据库及节点启动并发限制为两组。JDBC/Hikari 建连等待 10s、socket 等待 15s、Flyway 启动连接重试三次，所有等待保留在实际诊断中。
- REST 请求等待 30s，Docker 容器所有权查询各等待 90s；故障后的超时仍记为 RECOVERY_FAILED。故障前的瞬时准备错误最多在新 conversation 上准备三次，原始准备观察保留；已经注入的恢复样本不会被重新执行以替换失败结果。
- WebSocket 只重试瞬时连接错误，最多三次并记录次数；鉴权、协议和作用域错误直接失败。异步错误记录安全的底层异常类型或错误码。
- CompleteReport 仅补执行原计划中尚未注入的 NOT_RUN 项，核验冻结计划、原始汇总、组件与配置 SHA-256。已有恢复失败或未验证故障时拒绝补执行；原报告及错误证据不可覆盖。

## 尚存风险与下一模块确认事项

- 此 profile 验证存储与恢复机制；生产模型、工具服务、生产租约和真实部署的数字需要单独实测。
- 2026-10-02 历史中断数据不能证明严格 `<0.5%` 达标；其中 `RECOVERY-00342` 的 WebSocket 校验出现 `ExecutionException`，故障未完整验证，仍保留为 RECOVERY_FAILED。
- 2026-10-02 历史复测请求创建并发 8 个实例，其中 6 个产出样本；另两组 MySQL/Flyway 启动连接错误及原始诊断日志保留。当前运行使用并发 4，其他宿主资源与并发配置需要分别实测。
- 当前 M17 Worker 与 API 同 JVM，使用 MySQL 持久任务和本地执行器；Worker 故障包含同进程 API 重启，没有声称 Agent RabbitMQ 消费验证。
- PAUSED 执行经生产 resume API 恢复；若后续要求独立 Worker 或自动恢复，应先确认产品行为与拓扑。
- durable execution 最终结果保存在 `agent_execution.result_json`；此前完成的会话消息另行核验。下一模块若要求执行结果自动写回会话，需要先确认。
- 默认移除本次容器并保留本次 MySQL volume。所有权标识和诊断日志位置保留在最终报告中。
