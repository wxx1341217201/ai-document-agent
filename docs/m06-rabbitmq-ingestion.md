# M06：RabbitMQ 异步摄取流水线

## 处理链路

上传事务只保存 `document`、创建 `document_ingestion_job` 和一条事务 outbox 事件，HTTP 响应不会等待解析、切分或索引。提交后异步调度器等待 RabbitMQ publisher confirm；只有 confirm ACK 后，文档任务才从 `UPLOADED` 进入 `QUEUED`。

文档协调消息执行解析和切分，并在同一数据库事务中写入 `document_chunk`、`document_batch_task` 和每个 batch 的 outbox 事件。假设切分出 1,200 个 chunk 且 `app.ingestion.batch-size=50`，会创建 24 个 batch；多个 RabbitMQ consumer 可并行消费这些 batch。

M06 的 `ChunkBatchIndexingProcessor` 默认是无外部依赖的占位实现，只验证并调度持久化 batch。M07 与 M08 应以 `batchId` 作为幂等键替换此端口，分别接入向量和 BM25 索引，不能修改 M06 的任务/消息状态机。

## 消息和 RabbitMQ 拓扑

消息均为 JSON，版本字段目前固定为 `schemaVersion: 1`，且只包含 UUID、文档/知识库标识、控制信息和 chunk index 范围。不会发送原始文件或 chunk 正文。

| 用途 | 配置项默认值 |
| --- | --- |
| 文档 Exchange / 主队列 | `document.ingestion.exchange` / `document.ingestion.queue` |
| 文档重试 / 死信队列 | `document.ingestion.retry.queue` / `document.ingestion.dlq` |
| Batch Exchange / 主队列 | `document.batch.exchange` / `document.batch.queue` |
| Batch 重试 / 死信队列 | `document.batch.retry.queue` / `document.batch.dlq` |

所有交换机与队列均为 durable；主队列拒绝的消息经 DLX 进入对应 DLQ。重试消息先投递到持久化重试队列，按单消息 TTL 实现有限指数退避，TTL 到期后死信回流主交换机。`attempt` 从 0 开始，`app.ingestion.max-attempts` 表示总处理次数上限，默认 3 次。非可重试错误、超出上限的错误和不合法消息不会无限循环。

RabbitMQ 服务由 `compose.yaml` 的 `rabbitmq:4.1-management` 提供：AMQP 端口为 5672、管理端口为 15672、数据写入 `rabbitmq_data` 持久卷，并带有 `rabbitmq-diagnostics -q ping` 健康检查。凭据通过 `RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD` 和 `RABBITMQ_VHOST` 环境变量注入；示例值仅位于 `.env.example`，不得提交真实口令。

## 一致性、幂等与恢复

本模块实现了事务 outbox，而不是依赖“数据库提交后立即发送”这一不可靠窗口：

1. 任务状态和 outbox 事件在一个数据库事务中写入。
2. outbox 使用数据库租约领取；进程崩溃后租约超时会重新扫描。
3. publisher confirm 成功后才标记 outbox `PUBLISHED` 并推进主队列状态。
4. confirm 成功但状态回写前崩溃时，outbox 可能重发；文档 job 和 batch task 的数据库状态机使这种投递安全幂等。

`document_ingestion_job` 以 `(document_id, operation)` 唯一约束保证同一文档同一操作只有一个有效协调任务；`document_batch_task` 以 `batch_id` 和 `(job_id, batch_no)` 唯一约束保证 batch 幂等。消费者用 `knowledgeBaseId` 限定任务、文档和 chunk 查询；不匹配消息直接进入 DLQ。

聚合时会锁定 job 行并在事务中从 `document_batch_task` 计算完成/失败数，而不是使用 JVM 计数器。只有 `completed == total` 且 `failed == 0` 才将 job 和 document 标记为 `READY`。任一 batch 永久失败会把 job/document 标记为 `FAILED`；可通过：

`POST /api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/ingestion/retry`

手动恢复。若解析阶段失败，会重投文档协调任务；若已经生成 batch，只重投未完成 batch，不重复切分已有 chunk。

`enqueued_at`、`started_at` 与 `completed_at` 保存在 job 和 batch 表中，分别记录经 confirm 的排队时间、首次开始处理时间和最终结束时间。M19E 必须基于这些字段计算等待时间与吞吐量，不得人工拼接时间。

## 运维参数

下列配置集中在 `app.ingestion`，可通过同名环境变量覆盖：

- `batch-size`：长文档每个 `ChunkBatchTask` 的 chunk 数，默认 50。
- `listener-concurrency`、`listener-max-concurrency`、`prefetch`：RabbitMQ worker pool 参数。
- `max-attempts`、`retry-initial-delay`、`retry-max-delay`：有限重试策略。
- `publisher-confirm-timeout`：发布确认等待上限。
- `outbox.scan-delay`、`outbox.dispatch-lease`、`outbox.max-publish-attempts`：outbox 补偿与发布失败限制。
- `stale-processing-timeout`：消费者在 ACK 前中断后的持久化恢复阈值。

上述吞吐量/并发参数仅为保守默认值，M19E 应通过压测和持久化时间数据调整。
