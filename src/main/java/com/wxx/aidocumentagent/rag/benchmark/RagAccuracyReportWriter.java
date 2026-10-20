package com.wxx.aidocumentagent.rag.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.FactReview;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.Review;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyReport.Summary;
import tools.jackson.databind.ObjectMapper;

public final class RagAccuracyReportWriter {

    private final ObjectMapper mapper;

    public RagAccuracyReportWriter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public Path write(Path directory, RagAccuracyReport report) throws IOException {
        Files.createDirectories(directory);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String stem = "rag-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path json = directory.resolve(stem + ".json");
        Files.writeString(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve(stem + ".md"), markdown(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        List<String> failures = report.failureDetails().stream().map(mapper::writeValueAsString).toList();
        Files.writeString(directory.resolve(stem + "-failures.jsonl"), String.join("\n", failures)
                        + (failures.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        writeReviewTemplate(directory.resolve(stem + "-review-template.jsonl"), report);
        return json;
    }

    private void writeReviewTemplate(Path path, RagAccuracyReport report) throws IOException {
        List<String> lines = new ArrayList<>();
        for (var item : report.cases()) {
            if (!item.observation().executed() || item.observation().errorCode() != null) {
                continue;
            }
            Review review = new Review(report.runId(), report.dataset().sha256(), item.sample().id(),
                    item.resultSha256(), "", null, null,
                    item.sample().requiredFacts().stream().map(fact -> new FactReview(fact.id(), null, List.of())).toList(),
                    null, null, null, "");
            lines.add(mapper.writeValueAsString(review));
        }
        Files.writeString(path, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private String markdown(RagAccuracyReport report) {
        StringBuilder result = new StringBuilder("# M19B RAG Accuracy Benchmark\n\n")
                .append("- Run: `").append(report.runId()).append("`\n")
                .append("- 状态: `").append(report.status()).append("`\n")
                .append("- QA Accuracy ≥ 83%: `").append(report.targetStatus()).append("`\n")
                .append("- 数据集 SHA-256: `").append(report.dataset().sha256()).append("`\n")
                .append("- 知识库: ").append(report.dataset().knowledgeBaseId()).append("\n")
                .append("- 标签来源: `").append(report.dataset().labelProvenance()).append("`\n")
                .append("- 判定版本: `").append(report.policyVersion()).append("`\n\n")
                .append("QA Accuracy = CORRECT 的可回答样本数 / 可回答样本总数。拒答准确率单独统计。")
                .append("词组匹配仅提供复核建议；CORRECT 必须满足事实覆盖、无关键冲突、核心陈述有引用支撑、引用来自本次上下文。")
                .append("人工复核未完成或样本未执行时，最终准确率为 N/A；调用错误计入分母并使运行不能用于目标声明。\n\n")
                .append("## 总体与题型\n\n")
                .append("| 题型 | 可回答 | CORRECT | QA Accuracy | 无答案 | 正确拒答 | 拒答准确率 | 待复核 | 错误 | 未执行 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        appendSummary(result, "总体", report.overall());
        for (var type : RagAccuracyDataset.QuestionType.values()) {
            appendSummary(result, type.name(), report.byType().get(type));
        }
        result.append("\n可回答题当前已确认准确率范围: ")
                .append(percent(report.overall().qaAccuracyLowerBound())).append(" — ")
                .append(percent(report.overall().qaAccuracyUpperBound())).append("。")
                .append("此范围包含尚未复核/执行的样本，不能作为已测准确率。\n\n")
                .append("## 配置\n\n");
        report.configuration().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> result.append("- ").append(cell(entry.getKey())).append(": `")
                        .append(cell(String.valueOf(entry.getValue()))).append("`\n"));
        result.append("\n## 限制\n\n");
        if (report.limitations().isEmpty()) {
            result.append("无额外限制。\n");
        }
        else {
            report.limitations().forEach(item -> result.append("- ").append(cell(item)).append('\n'));
        }
        result.append("\n## 错题与调用错误\n\n")
                .append("完整问题、参考答案、必需事实、实际答案、引用、截断后上下文及失败原因见同名 JSON 的 cases / failureDetails。")
                .append("未执行样本: ").append(report.overall().notRun()).append("；待人工复核: ")
                .append(report.overall().pendingReview()).append("。\n\n")
                .append("| ID | 问题 | 答案 | 引用 chunk | 失败原因 |\n|---|---|---|---|---|\n");
        report.failureDetails().stream().filter(item -> item.status() != RagAccuracyEvaluator.Status.NOT_RUN)
                .forEach(item -> result.append("| ").append(cell(item.caseId())).append(" | ")
                        .append(cell(item.question())).append(" | ").append(cell(item.answer())).append(" | ")
                        .append(item.citations().stream().map(citation -> Long.toString(citation.chunkId())).toList())
                        .append(" | ").append(cell(String.join(", ", item.failureReasons()))).append(" |\n"));
        return result.toString();
    }

    private void appendSummary(StringBuilder result, String name, Summary summary) {
        result.append("| ").append(name).append(" | ").append(summary.answerable()).append(" | ")
                .append(summary.correct()).append(" | ").append(percent(summary.qaAccuracy())).append(" | ")
                .append(summary.unanswerable()).append(" | ").append(summary.refusalCorrect()).append(" | ")
                .append(percent(summary.refusalAccuracy())).append(" | ").append(summary.pendingReview()).append(" | ")
                .append(summary.errors()).append(" | ").append(summary.notRun()).append(" |\n");
    }

    private String percent(Double value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("`", "'");
    }
}
