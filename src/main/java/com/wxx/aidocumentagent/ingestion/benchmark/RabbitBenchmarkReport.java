package com.wxx.aidocumentagent.ingestion.benchmark;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class RabbitBenchmarkReport {
    private RabbitBenchmarkReport() { }
    private static boolean eligible(JsonNode row) {
        return row.path("evaluation").path("valid").asBoolean() && row.path("evaluation").path("peakStableWindow").isObject()
                && row.path("cleanup").asString().equals("COMPLETED");
    }
    private static double peak(JsonNode row) {
        return row.path("evaluation").path("peakStableWindow").path("documentsPerSecond").asDouble();
    }
    public static JsonNode summary(JsonNode plan, JsonNode rows) {
        JsonNode sync = null, async = null, sync50 = null, async50 = null;
        boolean allUsable = rows.size() == 15 && plan.path("matrix").size() == 15;
        for (JsonNode row : rows) {
            allUsable &= eligible(row);
            if (!eligible(row)) continue;
            boolean isSync = row.path("config").path("mode").asString().equals("sync");
            if (isSync && (sync == null || peak(row) > peak(sync))) sync = row;
            if (!isSync && (async == null || peak(row) > peak(async))) async = row;
            if (row.path("config").path("batchSize").asInt() == 50) {
                if (isSync) sync50 = row;
                else if (async50 == null || peak(row) > peak(async50)) async50 = row;
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("formalMatrixComplete", allUsable);
        Double gain = sync == null || async == null ? null : peak(async) / peak(sync);
        result.put("throughputMetric", "completed documents/second over a stable 60s window");
        result.put("throughputGain", gain);
        result.put("syncPeakDocumentsPerSecond", sync == null ? null : peak(sync));
        result.put("asyncPeakDocumentsPerSecond", async == null ? null : peak(async));
        result.put("syncPeakConfig", sync == null ? null : sync.path("config"));
        result.put("asyncPeakConfig", async == null ? null : async.path("config"));
        result.put("throughputTarget", !allUsable || gain == null ? "NOT_EVALUABLE" : gain >= 2.3 ? "MET" : "NOT_MET");
        Double from = sync50 == null ? null : sync50.path("evaluation").path("submitAckLatencyMs").path("mean").asDouble();
        Double to = async50 == null ? null : async50.path("evaluation").path("submitAckLatencyMs").path("mean").asDouble();
        result.put("waitMetric", "submit_ack_latency"); result.put("syncSubmitAckMeanMs", from); result.put("asyncSubmitAckMeanMs", to);
        result.put("waitComparisonConfig", async50 == null ? null : async50.path("config"));
        // Freeze the meaning of 'about 7.25 seconds': +/-20%, and asynchronous ACK <=250ms.
        result.put("waitTarget", !allUsable || from == null || to == null ? "NOT_EVALUABLE"
                : from >= 5800 && from <= 8700 && to <= 250 ? "MET" : "NOT_MET");
        return JsonMapper.builder().build().valueToTree(result);
    }

    public static void writeCsv(Path directory, String id, JsonNode raw, JsonNode evaluated) throws Exception {
        var documents = new StringBuilder("document_id,corpus_id,round,bytes,arrived_at_epoch_ms,ack_at_epoch_ms,ready_at_epoch_ms,submit_ack_latency_ms,end_to_end_latency_ms\n");
        for (JsonNode doc : raw.path("documents")) {
            documents.append(csv(doc.path("documentId"))).append(',').append(csv(doc.path("corpusId"))).append(',')
                    .append(csv(doc.path("round"))).append(',').append(csv(doc.path("bytes"))).append(',')
                    .append(csv(doc.path("arrivedAt"))).append(',').append(csv(doc.path("ackAt"))).append(',').append(csv(doc.path("readyAt")))
                    .append(',').append(difference(doc, "ackAt", "arrivedAt")).append(',').append(difference(doc, "readyAt", "arrivedAt")).append('\n');
        }
        Files.writeString(directory.resolve(id + "-documents.csv"), documents, StandardCharsets.UTF_8);
        writeFields(directory.resolve(id + "-batches.csv"), evaluated.path("logicalBatches"), List.of(
                "batchId", "documentId", "chunks", "enqueuedAt", "startedAt", "completedAt", "queueWaitMs", "processingMs"));
        writeFields(directory.resolve(id + "-stages.csv"), raw.path("stages"), List.of(
                "batchId", "stage", "documentId", "chunks", "enqueuedAt", "startedAt", "completedAt"));
        writeFields(directory.resolve(id + "-clients.csv"), raw.path("clientObservations"), List.of(
                "sequence", "corpusId", "round", "documentId", "startedAt", "finishedAt", "httpLatencyMs", "success", "error"));
        writeFields(directory.resolve(id + "-windows.csv"), evaluated.path("windows"), List.of(
                "from", "to", "stable", "documentsPerSecond", "batchesPerSecond", "chunksPerSecond", "errorRate",
                "completionBinCv", "batchBacklogGrowth", "sampleCoverage"));
        var samples = new StringBuilder("at_epoch_ms,ready_documents,completed_batches,completed_chunks,failed_documents,scheduled_retry_messages,publish_retry_attempts,document_backlog,batch_backlog,cpu_one_core_pct,cpu_four_core_pct,heap_used_bytes,non_heap_used_bytes,rabbit_ready,rabbit_unacknowledged,rabbit_dead_letters\n");
        JsonNode previous = null;
        for (JsonNode sample : raw.path("samples")) {
            double oneCore = previous == null ? 0 : 100.0 * (sample.path("processCpuTimeNanos").asLong() - previous.path("processCpuTimeNanos").asLong())
                    / ((sample.path("at").asDouble() - previous.path("at").asDouble()) * 1_000_000.0);
            long ready = 0, unacked = 0, dlq = 0;
            for (JsonNode queue : sample.path("rabbitQueues")) {
                ready += queue.path("ready").asLong(); unacked += queue.path("unacknowledged").asLong();
                if (queue.path("name").asString().endsWith(".dlq")) dlq += queue.path("ready").asLong();
            }
            for (String field : List.of("at", "ready", "completedBatches", "completedChunks", "failedDocuments", "retryAttempts", "publishRetryAttempts", "documentBacklog", "batchBacklog"))
                samples.append(csv(sample.path(field))).append(',');
            samples.append(oneCore).append(',').append(oneCore / 4).append(',').append(csv(sample.path("heapUsedBytes")))
                    .append(',').append(csv(sample.path("nonHeapUsedBytes"))).append(',').append(ready).append(',').append(unacked).append(',').append(dlq).append('\n');
            previous = sample;
        }
        Files.writeString(directory.resolve(id + "-resources.csv"), samples, StandardCharsets.UTF_8);
    }
    private static String difference(JsonNode node, String end, String start) {
        return node.path(end).isNumber() && node.path(start).isNumber()
                ? Double.toString(node.path(end).asDouble() - node.path(start).asDouble()) : "";
    }
    private static String csv(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "";
        String text = value.isString() ? value.asString() : value.toString();
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    private static void writeFields(Path file, JsonNode rows, List<String> fields) throws Exception {
        var text = new StringBuilder(String.join(",", fields)).append('\n');
        for (JsonNode row : rows) text.append(fields.stream().map(f -> csv(row.path(f))).collect(java.util.stream.Collectors.joining(","))).append('\n');
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
    private static String number(JsonNode value) { return value.isNumber() ? String.format(Locale.ROOT, "%.2f", value.asDouble()) : "N/A"; }
    private static String configId(JsonNode config) {
        return config.path("mode").asString() + "-b" + config.path("batchSize").asInt() + "-c" + config.path("consumers").asInt();
    }

    public static void writeReport(Path directory, JsonNode plan, JsonNode rows) throws Exception {
        JsonNode comparison = summary(plan, rows);
        var text = new StringBuilder("# M19E：RabbitMQ Performance Benchmark\n\n");
        text.append("Run ID：`").append(plan.path("runId").asString()).append("`；profile：`DETERMINISTIC_LOCAL_INDEX_REAL_MYSQL_RABBITMQ`。\n\n")
                .append("真实 MySQL、磁盘文件、生产 TXT 解析/切分、Outbox、RabbitMQ、持久任务状态机与 DOCUMENT_READY 聚合；32 维确定性本地 embedding、MySQL 中的向量/关键词测试索引，两组完全相同。未调用外部模型、Qdrant、Elasticsearch 或 reranker；这些结果不能冒充真实供应商或完整生产索引的性能。\n\n")
                .append("## 指标口径\n\n")
                .append("| 指标 | 边界 |\n|---|---|\n")
                .append("| submit_ack_latency | 服务端 Filter 收到请求（multipart 解析之前）→持久化已提交且响应序列化完成；A 包含串行排队和全部处理，B 在文件、元数据、任务及 Outbox 持久化后返回 |\n")
                .append("| client_http_latency | 客户端开始构造/发送请求→收到完整 HTTP 响应，单独记录 |\n")
                .append("| queue_wait_latency | 首个 VECTOR batch 的持久 enqueued_at（A inline dispatch、B publisher confirm 回写）→同一批次首个 started_at；不含上传、文档协调队列和 Outbox 发布前等待 |\n")
                .append("| processing_latency | 首个 VECTOR started_at→KEYWORD 完成事务提交；包含两个阶段及中间重新排队，stages.csv 可分别计算各阶段 |\n")
                .append("| end_to_end_latency | 服务端收到上传→最终任务/文档 READY 事务提交（对应 DOCUMENT_READY 持久状态，非客户端轮询或 WebSocket 网络送达） |\n")
                .append("| throughput | 连续 60s 内实际完成的文档/逻辑 batch/chunk 数 ÷60；一个 batch 的两个阶段只计一次 |\n\n")
                .append("所有时间是同机 UTC epoch 毫秒，原始 batch 时间来自生产状态字段，完成时间在 afterCommit 记录。B 的 HTTP ACK 表示持久 Outbox 接收成功，不表示 publisher confirm 或消费者完成；两种 AMQP ACK 的区别见 [RabbitMQ 官方文档](https://www.rabbitmq.com/docs/confirms)。prefetch 是每个消费者的上限，见 [官方 prefetch 文档](https://www.rabbitmq.com/docs/consumer-prefetch)。\n\n")
                .append("## 固定负载与环境\n\n")
                .append("100 份 SHA-256 固定 TXT；50 份长文档各 200 chunks，50 份短文档各 20 chunks。平均 110 chunks、56,320 bytes（55 KiB）；固定窗口 512 字符、无 overlap。长文档定义为 ≥200 chunks。每轮先预热 ")
                .append(plan.path("options").path("warmupDocuments").asInt()).append(" 份，正式至少覆盖全部 100 份，并持续提交 ")
                .append(plan.path("options").path("durationSeconds").asInt()).append("s。复用相同文件集合时创建本轮独立知识库，避免重复文件拒绝改变负载。预热、启动和结束 drain 不进入吞吐窗口。\n\n")
                .append("20 个客户端，最多 20 份未就绪文档：B 收到 ACK 后每 250ms 查询 READY 再提交下一份，A 在请求内处理完。此为有界闭环饱和负载；报告中的峰值限定于该矩阵/负载，没有使用无界提交制造积压。A 整个文档流程只有一条公平串行处理通道，B 的文档协调和 batch 两类消费者各为 concurrency=1/2/4/8，固定最小=最大；改善包含并行度变化，不能归因于 MQ 本身的固有加速。\n\n")
                .append("每行使用新建的同规格 Compose 环境和子 JVM，按固定 seed 打乱执行次序。同硬件、同文件、同模型策略：\n\n```json\n")
                .append(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(plan.path("hardware"))).append("\n```\n\n")
                .append("prefetch=").append(plan.path("options").path("prefetch").asInt()).append("；VECTOR 固定延迟 ")
                .append(plan.path("options").path("chunkDelayMs").asInt()).append("ms/chunk，每阶段另加 ")
                .append(plan.path("options").path("batchDelayMs").asInt()).append("ms/batch。A/B 均持久化实际确定性索引。外部供应商配额/限流：不适用；真实重试计数保留在资源表。JVM CPU 由进程 CPU 时间差测量，内存为 heap/non-heap 已用字节，不是整机或容器 RSS。RabbitMQ 队列数据取 Management API，默认统计刷新约 5s，1s 采样可能重复同一水位。\n\n")
                .append("## 稳定窗口与判定\n\n")
                .append("每个 1s 采样起点检查连续 60s：完整采样间隔 ≤2.5s，完成文档的四个 15s 子窗 CV≤0.35，错误率≤")
                .append(plan.path("options").path("errorThreshold").asDouble()).append("，batch 积压增长≤160（20 个在途长文档 ×最大 8 batches）。同一配置还须覆盖 100 份不同 corpus 文档、无终态失败/缺失指标、持久 chunk 数=完整索引数且全部 drain。错误分母为窗口内结束的客户端文档操作（成功 READY 或失败），不是 ACK 数。异常和超时不会丢弃。\n\n")
                .append("主比较使用 **文档/s**，避免 batchSize 改变导致 batch/s 不可比：`ThroughputGain=AsyncPeakStableDocumentsPerSecond/SyncPeakStableDocumentsPerSecond`。不同 batchSize 的 batch/s 仅在同 batchSize 的 A/B 内比较。只有全部 15 行有效且各有稳定窗口才给正式目标判定；短诊断、部分矩阵或中断为 NOT_EVALUABLE。\n\n")
                .append("等待目标固定比较 batchSize=50 的同步组与该 batchSize 吞吐最高的异步组：同步均值 7.25s±20% 且异步均值≤0.25s。实测值不会被替换成目标值；简历引用必须同时注明本测试 profile 和单通道串行基线。\n\n")
                .append("## 实验矩阵\n\n| 配置 | ACK mean / P95 ms | 队列 wait mean / P95 ms | processing mean / P95 ms | E2E mean / P95 ms | 稳定 doc/s | 稳定 batch/s | 错误/操作 |\n|---|---:|---:|---:|---:|---:|---:|---:|\n");
        var matrixCsv = new StringBuilder("config,ack_mean_ms,ack_p95_ms,queue_mean_ms,queue_p95_ms,processing_mean_ms,processing_p95_ms,e2e_mean_ms,e2e_p95_ms,peak_docs_s,peak_batches_s,errors,operations\n");
        for (JsonNode row : rows) {
            JsonNode evaluation = row.path("evaluation");
            String id = configId(row.path("config"));
            text.append("| ").append(id).append(" | "); matrixCsv.append(id).append(',');
            for (String metric : List.of("submitAckLatencyMs", "queueWaitLatencyMs", "processingLatencyMs", "endToEndLatencyMs")) {
                text.append(number(evaluation.path(metric).path("mean"))).append(" / ").append(number(evaluation.path(metric).path("p95"))).append(" | ");
                matrixCsv.append(number(evaluation.path(metric).path("mean"))).append(',').append(number(evaluation.path(metric).path("p95"))).append(',');
            }
            JsonNode window = evaluation.path("peakStableWindow");
            text.append(number(window.path("documentsPerSecond"))).append(" | ").append(number(window.path("batchesPerSecond")))
                    .append(" | ").append(evaluation.path("errors").asInt()).append('/').append(evaluation.path("submittedDocuments").asInt()).append(" |\n");
            matrixCsv.append(number(window.path("documentsPerSecond"))).append(',').append(number(window.path("batchesPerSecond"))).append(',')
                    .append(evaluation.path("errors").asInt()).append(',').append(evaluation.path("submittedDocuments").asInt()).append('\n');
            if (row.has("error")) text.append("\n").append(id).append("：环境/执行失败，").append(row.path("error").asString()).append("。\n\n");
        }
        text.append("\n## 资源与实际负载\n\n| 配置 | JVM CPU mean（单核%） | Heap max MiB | 逻辑 batch 积压 max | 实际 chunk/文档 mean | 实际 bytes/文档 mean |\n|---|---:|---:|---:|---:|---:|\n");
        for (JsonNode row : rows) {
            JsonNode evaluation = row.path("evaluation");
            text.append("| ").append(configId(row.path("config"))).append(" | ")
                    .append(number(evaluation.path("cpuOneCorePercent").path("mean"))).append(" | ")
                    .append(evaluation.path("heapUsedBytes").path("max").isNumber() ? String.format(Locale.ROOT, "%.2f", evaluation.path("heapUsedBytes").path("max").asDouble()/1048576) : "N/A").append(" | ")
                    .append(number(evaluation.path("batchQueueDepth").path("max"))).append(" | ")
                    .append(number(evaluation.path("actualChunksPerDocument").path("mean"))).append(" | ")
                    .append(number(evaluation.path("actualFileBytes").path("mean"))).append(" |\n");
        }
        text.append("\n## 结果\n\n稳定吞吐倍数：**").append(number(comparison.path("throughputGain"))).append("×**，≥2.3× 目标 **")
                .append(comparison.path("throughputTarget").asString()).append("**。\n\n")
                .append("batchSize=50 的 submit_ack_latency 实测均值：**").append(number(comparison.path("syncSubmitAckMeanMs")))
                .append("ms → ").append(number(comparison.path("asyncSubmitAckMeanMs"))).append("ms**；7.25s→0.25s 对齐目标 **")
                .append(comparison.path("waitTarget").asString()).append("**。\n\n")
                .append("资源/队列每秒数据在 `*-resources.csv`，每个候选稳定窗口在 `*-windows.csv`；服务端原始时间、逐阶段数据和客户端成功/失败分别在 documents/batches/stages/clients CSV 与同名原始 JSON。`plan.json` 保存完整负载、顺序、配置与源码 SHA-256；`*-client.jsonl`、`*-samples.jsonl` 为追加 journal，中断不能从未记录的样本推断成功。\n\n")
                .append("每行 cleanup 与环境 ID 在 `summary.json`；运行日志在该行的 runtimeDirectory。环境不可用或某行失败时保留错误，目标不判达标。生产负载、模型/索引服务或多通道同步基线需要重新跑对照实验。\n");
        Files.writeString(directory.resolve("matrix.csv"), matrixCsv, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("report.md"), text, StandardCharsets.UTF_8);
    }
}
