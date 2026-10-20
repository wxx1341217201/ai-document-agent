# M19E 交付记录（2026-10-11）

完整 15 组真实 RabbitMQ/MySQL 矩阵已完成，15 组均有效、有连续 60s 稳定窗口且清理成功。正式 Run ID：`156e3c39-edc3-42ae-9261-11ff1177a23f`。

[正式报告](results/run-20261011T091207Z-156e3c39/report.md)、[汇总 JSON](results/run-20261011T091207Z-156e3c39/summary.json)、[矩阵 CSV](results/run-20261011T091207Z-156e3c39/matrix.csv)、[独立核验](results/run-20261011T091207Z-156e3c39/audit.json)。

## 实测结果与目标

| 比较 | 同步 | 异步 | 判定 |
|---|---:|---:|---|
| 上传请求平均阻塞时间，`submit_ack_latency`；batchSize=50，单通道 A →8 消费者 B | 16,080.00ms | 38.31ms | 异步 ≤250ms；7.25s±20% 基线未复现，等待对齐目标 NOT_MET |
| 同一对照的 ACK P95 | 19,453.45ms | 69.35ms | 逐文档原始时间保留 |
| 相同 batchSize=50 的稳定完成吞吐 | 1.20 文档/s | 5.25 文档/s | 4.375× |
| 全矩阵峰值稳定吞吐；batchSize=100，单通道 A →4 消费者 B | 1.50 文档/s | 8.3167 文档/s | **5.5444×，≥2.3× 目标 MET** |

主吞吐公式：`ThroughputGain=AsyncPeakStableDocumentsPerSecond/SyncPeakStableDocumentsPerSecond`。峰值来自符合固定稳定性条件的连续 60s，保留全部候选窗口，不使用瞬时最高值。两组峰值窗口分别完成 90 和 499 份文档。

等待和全矩阵吞吐使用不同配置，以上已分别注明。原目标中的 `7.25s →0.25s` 绑定 ACK 均值，报告采用实际的 `16.08s →38.31ms`，没有替换测量值。

## 五类指标对照

以下均为 batchSize=50：A 单通道同步串行，B 每类队列 8 个消费者；表内时间为 mean / P95，单位 ms。

| 指标 | A | B |
|---|---:|---:|
| submit_ack_latency | 16080.00 / 19453.45 | 38.31 / 69.35 |
| queue_wait_latency | 12.82 / 19.16 | 25.85 / 34.93 |
| processing_latency | 223.72 / 279.92 | 1863.74 / 2494.28 |
| end_to_end_latency | 16076.70 / 19445.98 | 3828.43 / 5121.56 |
| 稳定完成 throughput | 1.20 文档/s；2.95 batch/s | 5.25 文档/s；13.10 batch/s |

ACK 的起点是服务端收到请求、multipart 解析之前，终点是持久化提交并完成响应序列化。A 包含单通道排队和整份文档处理；B 在文件、元数据、任务与 Outbox 提交后返回。客户端 HTTP 耗时另存，不能互换。

逻辑 batch 的 queue_wait 从首个 VECTOR 的生产 `enqueued_at` 到首个 `started_at`，不含文档协调队列及 Outbox 发布前等待。processing 到最终 KEYWORD 完成事务提交，包含阶段之间重新排队。异步组的这两个 batch 指标较高；E2E 的起点始终为文档提交，终点为最终 READY 事务提交。逐阶段 CSV 可单独计算每个阶段的时间。

## Profile、负载与解释范围

- Profile 为 `DETERMINISTIC_LOCAL_INDEX_REAL_MYSQL_RABBITMQ`：生产磁盘上传、TXT 解析、切分、Outbox、持久状态机、真实 RabbitMQ 和 MySQL；32 维确定性 embedding 与 MySQL 向量/关键词测试索引。VECTOR 固定 2ms/chunk，各阶段另加 2ms/batch；全量重算并验证索引内容。
- 冻结 100 份 TXT，50 份长文档各 200 chunks、50 份短文档各 20 chunks；平均 110 chunks、56,320 bytes。使用生产 512 字符窗口、零 overlap。每组预热 20 份，正式持续提交至少 90s 并至少覆盖完整 corpus。
- 20 个在途文档的闭环负载；B ACK 后每 250ms 检查 READY 再释放提交槽。反复使用相同 100 份文件，按轮创建独立知识库。15 组累计 **4,610 次正式文档操作、507,640 个 chunk**；预热单独排除。错误为 **0/4,610**，终态失败、业务重试和发布重试均为 0。
- batchSize=25/50/100；异步 document/batch 两类消费者各为 1/2/4/8；prefetch=2/消费者。同步每个 batchSize 一组。固定 seed=20261011 打乱顺序，每组独立、同规格容器与 JVM。
- 同一台 Windows 11 主机、12 逻辑 CPU；JDK 21.0.12；JVM heap=512MiB、ActiveProcessorCount=4；MySQL/RabbitMQ 容器各限制 2 CPU/1GiB，保留 REPEATABLE_READ。完整硬件、配置、源码与编译字节码 SHA-256 在 plan/summary 中，Docker 镜像身份在 audit 中。
- CPU 为应用 JVM 进程占用：单核=100%，图表再除以 4。ActiveProcessorCount 用于 JVM 处理器识别，应用仍运行在 12 逻辑 CPU 的主机上，因此归一化数值可能超过 100%。内存为 heap/non-heap，未测 RSS。RabbitMQ Management 统计约每 5s 更新，1s 样本可能重复水位。
- 结果包含从一条串行通道到多消费者 Worker Pool 的并行度变化。未调用外部 embedding、Qdrant、Elasticsearch 或 reranker；外部模型配额不适用。数字限定于这次受控矩阵，不作为真实供应商、完整生产索引或多通道同步基线的性能结论。

P95 使用 nearest-rank、包含全部正式提交；没有统计置信区间。稳定窗口要求采样间隔≤2.5s、四个 15s 完成子窗 CV≤0.35、错误率≤1%、batch 积压增长≤160，并严格排除 warmup 和结束 drain。全部行还须完成 corpus、全部 READY、双阶段完整、索引数量和内容正确。

## 已交付文件

- [运行说明](README.md)、[PowerShell 入口](run.ps1)、[隔离 Compose](compose.yaml)、[固定 corpus 清单](data/manifest.json) 与 100 份 TXT；兼容入口为 `scripts/run-rabbitmq-benchmark.ps1`。
- `src/main/resources/application-rabbitmq-benchmark{,-sync,-async}.yml`，由独立 `RabbitBenchmarkNode` 加载。Java runner、Controller、串行适配器、本地索引、事务提交后的指标采集、评估器和报告生成器位于 `ingestion/benchmark/`。
- 每个配置的原始 JSON、documents/batches/stages/clients/windows/resources CSV，以及追加 client/sample JSONL，全部在 [正式结果目录](results/run-20261011T091207Z-156e3c39/)。
- [总览 PNG](results/run-20261011T091207Z-156e3c39/overview.png) / [SVG](results/run-20261011T091207Z-156e3c39/overview.svg)，[资源曲线 PNG](results/run-20261011T091207Z-156e3c39/resources.png) / [SVG](results/run-20261011T091207Z-156e3c39/resources.svg)；由 [绘图脚本](plot_results.py) 生成。资源图相同行的纵轴范围一致，绿色标记选中稳定窗口，灰色标记 drain。图面已经检查。

## 实测修复与验证

首轮并发发现 MySQL 空范围 DELETE 的 gap lock 死锁和消费者先于 publisher-confirm 数据库回写领取任务的竞态。生产切分在首次摄取时跳过空 DELETE；两类消费者使用精确的 publication-pending 异常与有界确认等待门控，保留原有事务隔离、确认与失败处理。

同步适配器另修复 DATETIME(3) 舍入后立即领取 Outbox 可能短暂不可用的边界，最多等待 200ms，等待仍计入 ACK。Servlet 异常不会提前被记成成功 ACK。实际 RETRY 消息与重新发布尝试分别记录。

`.\mvnw.cmd -Dmaven.compiler.fork=true test`：**390 tests，0 failures，0 errors，0 skipped**。M19E 增加 18 项回归验证，覆盖指标分布/窗口、事务采集、确认门控、首次切分、inline 领取边界与失败 ACK；[测试证据](results/run-20261011T091207Z-156e3c39/test-evidence.json) 保存每个 suite 及 XML SHA-256。

正式运行退出 **0**；[独立 audit](results/run-20261011T091207Z-156e3c39/audit.json) **PASSED**，重算四类时间均值/P95、stage 身份、chunk/batch 数、稳定窗口完成数、CV/错误率/采样覆盖与吞吐公式，核验冻结源码/编译字节码、corpus、同一 Docker 镜像。 [清理证据](results/run-20261011T091207Z-156e3c39/cleanup-evidence.json) **PASSED**：15 行 cleanup=COMPLETED，残留 M19E 容器与评测 JVM 均为 0。

## 历史轮次与排除

| 结果目录 | 处理 |
|---|---|
| run-20261011T081918Z-2a78f269 | Validate；验证 100 份 corpus，未做性能测量 |
| run-20261011T081938Z-0e099973 | Docker 所有权模板校验失败；保留原始失败状态，标签核验后手工清理私有环境 |
| run-20261011T082131Z-473830db | 短诊断/部分矩阵；用于定位锁与确认竞态，不进入正式结论 |
| run-20261011T083008Z-0eb2810b | 完整旧轮次的 sync-b25 有一次 HTTP 500/缺失 READY；[exclusion.json](results/run-20261011T083008Z-0eb2810b/exclusion.json) 明确整轮排除 |
| run-20261011T091207Z-156e3c39 | 修复后重新执行全部 15 组；正式数据，未拼接旧轮次 |

## 复现

```powershell
.\benchmarks\rabbitmq\run.ps1 -Mode Validate
.\mvnw.cmd -Dmaven.compiler.fork=true test
.\benchmarks\rabbitmq\run.ps1 -Mode Live
.\benchmarks\rabbitmq\audit.ps1 -ReportDirectory 'benchmarks/rabbitmq/results/run-<timestamp>-<runId>'
```

默认 Live 即正式 15 组矩阵。`-RequireTargets` 同时要求等待和吞吐目标达成；本轮等待目标 NOT_MET，开启该门禁会按预期返回非零。绘图依赖及命令见 README 与 `requirements-plot.txt`，在计时结束后生成图表。

简历可用同配置表述：在确定性本地 embedding/测试索引 profile、100 份冻结文档和 20 并发提交下，RabbitMQ 分段 Worker Pool（batchSize=50、8 消费者/队列）相对单通道同步串行基线，将上传请求平均阻塞时间从 16.08s 降至 38.31ms，60s 稳定完成吞吐由 1.20 提升至 5.25 文档/s（4.38×）。面试时同时交代 ACK 与 READY 的区别及测试 profile。
