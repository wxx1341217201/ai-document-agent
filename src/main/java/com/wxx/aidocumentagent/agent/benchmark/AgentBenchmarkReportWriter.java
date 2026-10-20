package com.wxx.aidocumentagent.agent.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkEvaluator.Review;
import com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkReport.Summary;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.FactReview;
import tools.jackson.databind.ObjectMapper;

final class AgentBenchmarkReportWriter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private final ObjectMapper mapper;
    AgentBenchmarkReportWriter(ObjectMapper mapper) { this.mapper = mapper; }

    Path write(Path directory, AgentBenchmarkReport report) throws IOException {
        Files.createDirectories(directory);
        String stem = "agent-" + STAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path json = directory.resolve(stem + ".json");
        Files.writeString(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve(stem + ".md"), markdown(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        StringBuilder template = new StringBuilder();
        for (var item : report.cases()) {
            if (!item.observation().executed()) continue;
            var review = new Review(report.runId(), report.dataset().sha256(), item.task().caseId(), item.resultSha256(),
                    "", null, null, item.task().requiredFacts().stream().map(f -> new FactReview(f.id(), null, List.of())).toList(),
                    null, null, null, null, null, "");
            template.append(mapper.writeValueAsString(review)).append('\n');
        }
        Files.writeString(directory.resolve(stem + "-review-template.jsonl"), template, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve(stem + "-review-packet.md"), reviewPacket(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        StringBuilder failures = new StringBuilder();
        for (var failure : report.failureDetails()) failures.append(mapper.writeValueAsString(failure)).append('\n');
        Files.writeString(directory.resolve(stem + "-failures.jsonl"), failures, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return json;
    }

    private String reviewPacket(AgentBenchmarkReport report) {
        StringBuilder out = new StringBuilder("# M19C 多轮复核材料\n\n");
        out.append("runId: ").append(report.runId()).append("\n\n固定分母与标签，逐轮检查指代、工具处理及最终事实。")
                .append("摘要不自动判定语义正确；完整正文与未截断答案见 JSON 和 corpus 快照。\n");
        for (var item : report.cases()) {
            if (!item.observation().executed()) continue;
            out.append("\n## ").append(item.task().caseId()).append(" / ").append(item.task().mode()).append("\n\n")
                    .append("- 自动判定: ").append(item.evaluation().status()).append(" / ")
                    .append(String.join(", ", item.evaluation().failureReasons())).append('\n')
                    .append("- 参考答案: ").append(safe(item.task().referenceAnswer())).append('\n');
            for (var fact : item.task().requiredFacts()) {
                out.append("- 事实 ").append(fact.id()).append(": ").append(safe(fact.statement()))
                        .append(" / chunks ").append(fact.supportingChunkIds()).append(" / 源文: ")
                        .append(safe(fact.sourceQuote())).append('\n');
            }
            for (var turn : item.observation().turns()) {
                out.append("\n### T").append(turn.turn()).append("\n\n问题: ").append(safe(turn.question()))
                        .append("\n\n回答: ").append(safe(turn.result() == null ? null : turn.result().answer())).append("\n\n");
                for (var evidence : turn.evidence()) out.append("- ").append(evidence.citation().citationId())
                        .append(" / chunk ").append(evidence.citation().chunkId()).append(" / 实际上下文: ")
                        .append(safe(evidence.contextText())).append('\n');
            }
        }
        return out.toString();
    }

    private String markdown(AgentBenchmarkReport report) {
        StringBuilder out = new StringBuilder("# M19C Agent Task Benchmark\n\n");
        out.append("- runId: ").append(report.runId()).append("\n- 时间: ").append(report.createdAt())
                .append("\n- 状态: ").append(report.status()).append("\n- TaskSuccessRate ≥ 80% 目标: ").append(report.targetStatus())
                .append("\n- dataset SHA-256: ").append(report.dataset().sha256())
                .append("\n- 标注来源: ").append(report.dataset().labelProvenance()).append('\n');
        out.append("\n## 总体及各模式\n\nTaskSuccessRate = 成功任务数 / 固定任务总数。模型异常与未执行任务保留在分母中。")
                .append("未完成人工事实/全轮复核时为 N/A；自动门控通过不代表任务成功。\n\n")
                .append("| 模式 | 任务数 | 已执行 | 成功 | 失败 | 异常 | 待审 | 未执行 | 成功率 | 自动门控通过率 | 条件成功率范围 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        row(out, "总体", report.overall());
        for (AgentMode mode : AgentMode.values()) row(out, mode.name(), report.byMode().get(mode));
        out.append("\n自动门控通过率 = (SUCCEEDED + PENDING_REVIEW) / 固定任务总数，仅描述结构检查，不能代替 TaskSuccessRate。")
                .append("条件范围基于固定标签，未执行/待审计入上界；标签未核实时仍不能声明达标。\n\n");
        for (AgentMode mode : AgentMode.values()) out.append("- ").append(mode).append(" ≥ 80%: ")
                .append(report.targetByMode().get(mode)).append('\n');
        out.append("\n## 按场景\n\n| 场景 | 任务数 | 已执行 | 成功 | 失败 | 异常 | 待审 | 未执行 | 成功率 | 自动门控通过率 | 条件成功率范围 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        report.byTag().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> row(out, e.getKey(), e.getValue()));
        out.append("\n## 环境与限制\n\n");
        for (String limitation : report.limitations()) out.append("- ").append(safe(limitation)).append('\n');
        out.append("\n```json\n").append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.configuration())).append("\n```\n");
        out.append("\n## 失败任务 step / tool / error 摘要\n\n");
        if (report.failureDetails().isEmpty()) out.append("尚无已执行失败任务；未执行与待审不代表通过。\n");
        for (var failure : report.failureDetails()) {
            out.append("\n### ").append(failure.caseId()).append(" / ").append(failure.mode()).append("\n\n")
                    .append("- 原因: ").append(String.join(", ", failure.failureReasons())).append('\n');
            for (var turn : failure.turns()) {
                out.append("- T").append(turn.turn()).append(" 问题: ").append(safe(turn.question())).append('\n')
                        .append("- 答案: ").append(safe(turn.result() == null ? "无最终答案" : turn.result().answer())).append('\n')
                        .append("- 状态/错误: ").append(turn.result() == null ? "ERROR" : turn.result().status()).append(" / ").append(turn.errorCode()).append('\n');
                for (var step : turn.steps()) out.append("  - step ").append(step.stepId()).append(" / ").append(step.operation())
                        .append(" / ").append(step.status()).append(" / ").append(step.errorCode()).append('\n');
                for (var tool : turn.tools()) out.append("  - tool ").append(tool.toolName()).append(" / ").append(tool.status())
                        .append(" / ").append(tool.errorCode()).append(" / ").append(tool.elapsedMillis()).append("ms\n");
                if (turn.result() != null) for (var c : turn.result().citations()) out.append("  - citation ").append(c.citationId())
                        .append(" / KB ").append(c.knowledgeBaseId()).append(" / document ").append(c.documentId()).append(" / chunk ").append(c.chunkId()).append('\n');
            }
        }
        out.append("\n完整逐轮答案、引用、结构化步骤、工具安全摘要、证据与人工复核记录见同名 JSON。报告不保存模型隐藏思维链。\n");
        return out.toString();
    }
    private void row(StringBuilder out, String name, Summary s) {
        out.append('|').append(name).append('|').append(s.tasks()).append('|').append(s.executed()).append('|')
                .append(s.succeeded()).append('|').append(s.failed()).append('|').append(s.errors()).append('|')
                .append(s.pendingReview()).append('|').append(s.notRun()).append('|').append(rate(s.taskSuccessRate()))
                .append('|').append(rate(s.automaticGatePassRate())).append('|')
                .append(rate(s.successRateLowerBound())).append(" — ").append(rate(s.successRateUpperBound())).append("|\n");
    }
    private String rate(Double value) { return value == null ? "N/A" : String.format(Locale.ROOT, "%.2f%%", value * 100); }
    private String safe(String value) {
        if (value == null) return "无";
        String clipped = value.length() > 2000 ? value.substring(0, 2000) + "…" : value;
        return clipped.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ").replace("\r", " ");
    }
}
