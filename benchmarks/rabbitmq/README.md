# M19E：RabbitMQ Performance Benchmark

比较同一硬件、同一 100 文档集合上的同步串行路径与 RabbitMQ 分段 Worker Pool。入口调用生产上传、TXT 解析、切分、Outbox、任务状态机和 READY 聚合；索引端口替换为两组相同的确定性本地模型/测试索引。

已完成正式 15 组矩阵：ACK 均值 **16.08s →38.31ms**，全矩阵峰值稳定吞吐 **5.54×**，错误 **0/4,610**；7.25s 基线未复现，报告保留 NOT_MET。[交付记录](DELIVERY.md)、[正式报告](results/run-20261011T091207Z-156e3c39/report.md)、[独立核验](results/run-20261011T091207Z-156e3c39/audit.json)。

```powershell
# 不连接服务：生成/验证固定 corpus、生产切分数量、矩阵与冻结计划。
.\benchmarks\rabbitmq\run.ps1 -Mode Validate

# 正式矩阵：3 个同步基线 + 12 个异步组合，默认每行持续提交 90s。
.\benchmarks\rabbitmq\run.ps1 -Mode Live

# 先检查真实环境和采集；不足 60s 或部分矩阵不作正式目标判定。
.\benchmarks\rabbitmq\run.ps1 -Mode Live -BatchSizes 50 -Consumers 4 -DurationSeconds 15 -WarmupDocuments 4

# 回归门禁可显式要求两个目标均达标；没有实测或不达标则返回非零。
.\benchmarks\rabbitmq\run.ps1 -Mode Live -RequireTargets
```

兼容入口为 `scripts/run-rabbitmq-benchmark.ps1`。需要 Java 21、Maven Wrapper、Docker Desktop Linux 引擎，以及 `mysql:8.4` / `rabbitmq:4.1-management` 镜像。脚本不会读取生产 `.env`，没有外部模型网络调用，也没有外部供应商费用。

## 指标和目标

| 指标 | 实际记录的边界 |
|---|---|
| `submit_ack_latency` | 服务端收到请求，multipart 解析之前 →持久化提交并完成响应序列化；A 包含串行通道排队/全部处理，B 在持久 Outbox 接收后返回 |
| `client_http_latency` | 客户端开始构造/发送上传 →收到完整 HTTP 响应，单独记录 |
| `queue_wait_latency` | 逻辑 batch 首个 VECTOR `enqueued_at` →首个 VECTOR `started_at`；B 的 enqueue 是 publisher confirm 回写的生产字段，A 是 inline dispatch；不含上传或 Outbox 发布前等待 |
| `processing_latency` | 首个 VECTOR `started_at` →最终 KEYWORD 完成事务提交；包括阶段切换和中间重新入队，两阶段原始时间另存 |
| `end_to_end_latency` | 服务端收到文档 →文档/任务 READY 事务提交，等价于持久 `DOCUMENT_READY` 状态 |
| `throughput` | 稳定 60s 内实际完成的逻辑 batch、chunk 或文档数/60，一个 batch 的两阶段只计一次 |

`7.25s →0.25s` 固定绑定 **submit_ack_latency 的均值**，不能称为 MQ 队列等待。对齐判断为 batchSize=50 的同步均值 `7.25s±20%`，该 batchSize 吞吐最高的异步组均值 `≤0.25s`。数字由真实报告填入，不能用目标替换实测。

吞吐主指标为 **完成文档/s**：`ThroughputGain=AsyncPeakStableDocumentsPerSecond/SyncPeakStableDocumentsPerSecond`，目标 `≥2.3×`。不同 batchSize 的 batch/s 不可直接相除；报告同时记录 batch/s、chunk/s 供同粒度对照。

HTTP ACK 证明文件、元数据、任务和 Outbox 的持久接收，RabbitMQ publish 在提交后异步发生。它不同于 AMQP publisher confirm 和 consumer ACK；见 [官方确认机制](https://www.rabbitmq.com/docs/confirms)。`prefetch` 为每个消费者设置，见 [官方说明](https://www.rabbitmq.com/docs/consumer-prefetch)。

## 工作负载与公平性

- 固定 `data/` 内 100 份不同 SHA-256 的 TXT，50 份长文档各 200 chunks，50 份短文档各 20 chunks；平均 **110 chunks、56,320 bytes**。长文档定义为 ≥200 chunks。
- 使用生产 `fixed-window`、512 字符窗口、零 overlap；Validate 用生产解析器与切分器核验全部 100 份。
- 20 个客户端/最多 20 份未就绪文档的有界闭环负载。A 等待 HTTP 返回，B ACK 后以 250ms 间隔查询 READY，然后才提交下一份。
- 预热默认 20 份。正式提交至少覆盖完整 100 份，且持续默认 90s；需要持续负载时循环相同 corpus，按每 100 份新建独立知识库，避免重复文件规则影响实验。实际次数和 corpus 身份保留在 raw JSON/CSV。
- A 整个持久化→解析→切分→索引→响应流程仅有 **一条公平串行通道**，排队计入 HTTP 阻塞。B document 与 batch 两类消费者分别固定为 concurrency=1/2/4/8；矩阵清楚记录两类池大小。结果的吞吐改善包含并行度变化，不能作为 RabbitMQ 自身固定加速倍数，也不能用于声称优于 20 通道并发同步实现。
- 两组共用真实 MySQL、磁盘文件、生产业务状态机；32 维 embedding 由内容 SHA-256 确定，向量 JSON/关键词 digest 写入本轮 MySQL 测试索引。每行结束全量重算并核验内容、行数和索引完整性。
- 默认 VECTOR 2ms/chunk、各阶段另加 2ms/batch，延迟配置相同。此延迟为明确的测试参数，不是外部模型供应商的测量结果。reranker 不参与文档摄取，不人为加入它的延迟；外部配额/网络限流不适用。真实重试仍记录。
- 每行新建同规格 MySQL/RabbitMQ 容器与 JVM，按 seed=20261011 固定打乱顺序。JVM active processors=4、heap=512MiB；两容器各限制 2 CPU/1GiB。报告保存主机硬件、所有参数、代码/配置 SHA-256；不得混用不同 profile 的数据。

两套配置为 `application-rabbitmq-benchmark-sync.yml` / `application-rabbitmq-benchmark-async.yml`，共用隔离基准配置；仅由独立 `RabbitBenchmarkNode` 加载。正常 Spring 应用不会启用评测 Controller、模型或单通道基线。

## 稳定窗口

从每个 1s 采样起点寻找连续 60s 的最高 **完成文档/s**，并保留全部候选窗口：

1. 采样无缺口，间隔 ≤2.5s；窗口严格在持续提交区间内，启动/预热/结束 drain 不参与。
2. 窗口内结束的客户端文档操作错误率 ≤1%；HTTP、就绪超时、终态失败均保留，写请求不会偷偷重试。
3. 四个连续 15s 子窗口完成数的 CV≤0.35，排除瞬时 burst 和明显不稳定速率。
4. batch 积压增长≤160，即 20 在途长文档在 batchSize=25 时的最大 8 batches/文档。closed-loop 本身也限制在途文档数。
5. 整行至少覆盖 100 份不同 corpus 文档，时间边界齐全且合法，全部任务 drain，实际持久 chunk 数=完整测试索引数，无内容校验错误或终态失败。

只有全 15 行均有效且有稳定窗口才判定正式目标。短运行、部分矩阵、环境错误、中断为 **NOT_EVALUABLE**；有效完整实验达不到目标则为 **NOT_MET**，不能改写目标或丢弃慢样本。退出 0 表示数据/校验/清理成功；性能目标门禁通过 `-RequireTargets` 单独启用。

## 隔离与文件

每行创建唯一 `m19e-<environmentRunId>` Compose 项目，仅监听 loopback 自动选择空闲端口。随机数据库、RabbitMQ 口令及至少 32-byte 访问 token 通过子进程环境注入，不写入参数、计划或报告。子 JVM 使用独立配置；生产服务和已有知识库不参与。

启动、清理前核验容器 module/run/project 标签；仅停止该行自己创建的 JVM 和容器，`down -v` 删除该实验临时数据。清理后检查剩余容器为 0。若宿主进程被强制中断导致 hook 无法执行，可依据 plan/summary/runtime 中的确切项目 ID 先核验标签再清理；不要对主项目执行 `down`。

```text
benchmarks/rabbitmq/data/manifest.json       固定文档、文件大小、chunk 数和 SHA-256
benchmarks/rabbitmq/results/run-*/plan.json  硬件、profile、矩阵顺序、配置和源码指纹
benchmarks/rabbitmq/results/run-*/summary.json
benchmarks/rabbitmq/results/run-*/report.md
benchmarks/rabbitmq/results/run-*/matrix.csv
benchmarks/rabbitmq/results/run-*/<config>.json
benchmarks/rabbitmq/results/run-*/<config>-documents.csv
benchmarks/rabbitmq/results/run-*/<config>-batches.csv
benchmarks/rabbitmq/results/run-*/<config>-stages.csv
benchmarks/rabbitmq/results/run-*/<config>-clients.csv
benchmarks/rabbitmq/results/run-*/<config>-windows.csv
benchmarks/rabbitmq/results/run-*/<config>-resources.csv
benchmarks/rabbitmq/results/run-*/<config>-client.jsonl
benchmarks/rabbitmq/results/run-*/<config>-samples.jsonl
target/rabbitmq-benchmark/<runId>/<config>/  JVM/Compose/所有权核验日志
```

资源表每秒记录 JVM CPU、heap/non-heap 内存、真实 RabbitMQ ready/unacked/DLQ 深度、数据库逻辑 batch/文档积压、成功数及重试数。CPU 同时给出单核=100% 的进程占用和按 4 active processors 归一化的占用；内存不是 RSS。Management API 的默认统计刷新约 5s，因此连续 1s 采样可能显示同一水位。原始 epoch 毫秒时间和 progress 确认回调在同机 UTC，负数时间或缺失字段会导致验证失败。

ActiveProcessorCount=4 用于 JVM 处理器识别，应用在 12 逻辑 CPU 主机上运行；图表按 4 核归一化的 CPU 数值可能超过 100%。

## 实测发现的生产修复

首次切分不再对空 chunk 范围执行 DELETE：MySQL REPEATABLE_READ 下无匹配行的 DELETE 可能持有间隙锁，让同知识库的并发新文档插入互相死锁。新文档先作普通 existence 读取；有旧 chunk 时仍在同一事务删除和替换。评测保留并显式配置 REPEATABLE_READ，没有通过改变隔离级别绕过锁竞争。

消息可能比 publisher-confirm 数据库回写更早到达消费者。新增精确的 `IngestionPublicationPendingException` 与 `IngestionPublicationGate`，在已回滚的领取事务之外以 10ms 间隔等待，最长为 publisher confirm timeout（5s）。确认后只执行一次实际处理；超时、业务故障与中断仍进入原有限重试/死信处理，不提前处理未确认任务。两类生产消费者均使用该门控。

原始 `retryAttempts` 字段统计实际创建的 RETRY outbox 消息数（不是递增 attempt 序号的累计和）；`publishRetryAttempts` 单列再次发布尝试。早期短诊断未用于正式数字，最终结论以正式完整矩阵及其冻结源码为准。

同步评测适配器会在事务之外等待 `available_at` 最多 200ms，解决 MySQL `DATETIME(3)` 舍入后立即领取可能短暂返回空值的边界；该等待仍计入同步 ACK 阻塞，没有修改生产 Outbox 时间或偷偷重试上传。未处理的 Servlet 异常不会在 HTTP 500 写入前被错误计为成功 ACK。发现此问题的完整旧轮次保留 `exclusion.json`，全矩阵重新运行，不拼接旧轮次的有效行。

## 独立核验与图表

完成完整矩阵后，对实际结果目录执行：

```powershell
.\benchmarks\rabbitmq\audit.ps1 -ReportDirectory 'benchmarks/rabbitmq/results/run-<timestamp>-<runId>'
python .\benchmarks\rabbitmq\plot_results.py 'benchmarks/rabbitmq/results/run-<timestamp>-<runId>'
```

Audit 独立核验全部 corpus SHA-256、冻结源码/编译字节码、15 个唯一配置、同一 Docker 镜像身份、每份文档的实际 bytes/chunk 数、双阶段与逻辑 batch 数、四类延迟均值/P95、完整索引/预热排除、60s 完成数和最终吞吐公式，保存 `audit.json`。它不会将未完成矩阵或中断改判成功。

绘图需要 matplotlib，读取实际 summary/raw JSON，输出 `overview.png/.svg` 与 `resources.png/.svg`。overview 展示不同 batchSize/消费者的 ACK mean/P95、队列等待、E2E 和完成文档吞吐；resources 展示 batchSize=50 的同步与吞吐最高异步组合的 CPU、heap、队列和 15s 描述性完成速率。P95 不是置信区间，15s 描述性曲线不用于替代 60s 稳定吞吐判定。绿色区间为已选稳定窗口，灰色 drain 区间不计入吞吐；模型/索引 profile 在图上保留。

Python 3.12 绘图环境示例（在性能计时结束后执行）：

```powershell
py -3.12 -m venv .\target\rabbitmq-benchmark\plot-venv
$taskPlotPython = '.\target\rabbitmq-benchmark\plot-venv\Scripts\python.exe'
& $taskPlotPython -m pip install -r .\benchmarks\rabbitmq\requirements-plot.txt
& $taskPlotPython .\benchmarks\rabbitmq\plot_results.py 'benchmarks/rabbitmq/results/run-<timestamp>-<runId>'
```
