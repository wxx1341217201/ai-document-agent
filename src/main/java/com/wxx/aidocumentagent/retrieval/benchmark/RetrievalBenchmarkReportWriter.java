package com.wxx.aidocumentagent.retrieval.benchmark;

import java.util.Locale;
import java.util.Map;

final class RetrievalBenchmarkReportWriter {
    private RetrievalBenchmarkReportWriter() { }

    @SuppressWarnings("unchecked")
    static String markdown(Map<String, Object> report) {
        var out = new StringBuilder("# M19A Retrieval benchmark\n\n");
        out.append("Status: **").append(report.get("status")).append("**\n\n")
                .append("Run: `").append(report.get("runId")).append("`\n\n")
                .append("Dataset: ").append(report.get("dataset")).append("\n\n")
                .append("Corpus: ").append(report.get("corpus")).append("\n\n")
                .append("Label review: ").append(report.get("labelReview")).append("\n\n")
                .append("## Environment and configuration\n\n");
        var config = (Map<String, Object>) report.get("configuration");
        out.append("| Setting | Value |\n| --- | --- |\n");
        config.forEach((key, value) -> out.append("| ").append(key).append(" | ").append(value).append(" |\n"));
        out.append("\n## Four ablations\n\n| Variant | Status | Recall@5 | MRR@5 | nDCG@5 | Errors |\n")
                .append("| --- | --- | ---: | ---: | ---: | ---: |\n");
        var variants = (Map<String, Object>) report.get("variants");
        for (var variant : RetrievalBenchmarkRunner.Variant.values()) {
            var result = (Map<String, Object>) variants.get(variant.label);
            var scores = RetrievalBenchmarkRunner.scores(variants, variant);
            out.append("| ").append(variant.label).append(" | ").append(result == null ? "NOT_RUN" : result.get("status"));
            if (scores == null) { out.append(" | — | — | — | — |\n"); }
            else {
                var all = scores.get("all");
                out.append(" | ").append(percent(all.recallAt5())).append(" | ").append(number(all.mrrAt5()))
                        .append(" | ").append(number(all.ndcgAt5())).append(" | ")
                        .append(((java.util.List<?>) result.get("errors")).size()).append(" |\n");
            }
        }
        for (var variant : RetrievalBenchmarkRunner.Variant.values()) {
            var scores = RetrievalBenchmarkRunner.scores(variants, variant);
            if (scores == null) { continue; }
            out.append("\n## Tag buckets: ").append(variant.label).append("\n\n")
                    .append("| Tag | Answerable | Hits | Recall@5 | MRR@5 | nDCG@5 | No-answer empty / total |\n")
                    .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            scores.forEach((tag, score) -> out.append("| ").append(tag).append(" | ").append(score.answerable())
                    .append(" | ").append(score.hitsAt5()).append(" | ").append(percent(score.recallAt5()))
                    .append(" | ").append(number(score.mrrAt5())).append(" | ").append(number(score.ndcgAt5()))
                    .append(" | ").append(score.emptyResultsForUnanswerable()).append(" / ").append(score.unanswerable()).append(" |\n"));
        }
        out.append("\n## Retrieval latency\n\n").append(report.get("latencyDefinition")).append(".\n\n");
        var latency = (Map<String, Object>) report.get("latency");
        out.append("Status: ").append(latency.get("status")).append("\n\n");
        if (latency.containsKey("statistics")) {
            out.append("Warmup: ").append(latency.get("warmup")).append("; formal samples: ").append(latency.get("samples"))
                    .append("; concurrency: ").append(latency.get("concurrency")).append("; errors: ").append(latency.get("errors"))
                    .append("; error rate: ").append(latency.get("errorRate")).append(".\n\n")
                    .append("| Statistic | Full retrieval (ms) | Rerank portion (ms) |\n| --- | ---: | ---: |\n");
            var statistics = (Map<String, Double>) latency.get("statistics");
            var rerank = (Map<String, Double>) latency.get("rerankStatistics");
            for (String name : java.util.List.of("p50", "p95", "p99", "average", "max")) {
                out.append("| ").append(name).append(" | ").append(statistics.containsKey(name) ? number(statistics.get(name)) : "—")
                        .append(" | ").append(rerank.containsKey(name) ? number(rerank.get(name)) : "—").append(" |\n");
            }
        }
        out.append("\n## Assessment\n\n").append(report.get("targets")).append("\n\n")
                .append("Recall@5 target: >=91%; P95 target: <=490 ms. Threshold flags retain measured values. ")
                .append("Human label review is required before an achievement claim. Fake results are regression evidence only.\n\n")
                .append("Raw per-query and per-request evidence: `").append(report.get("observations")).append("`. ")
                .append("No-answer cases are excluded from Recall/MRR/nDCG denominators; MRR is truncated at rank 5. ")
                .append("Tags can overlap. Errors remain misses; invalid runs cannot support target claims.\n");
        var comparison = (Map<String, Object>) report.get("comparison");
        if (comparison != null && "COMPLETE".equals(comparison.get("status"))) {
            out.append("\n## Reranking improvement and regressions\n\n")
                    .append("Deltas are after minus before. Negative values identify a regression.\n\n")
                    .append("| Tag | Recall vs Vector (pp) | Recall vs RRF (pp) | MRR vs RRF | nDCG vs RRF |\n")
                    .append("| --- | ---: | ---: | ---: | ---: |\n");
            var deltas = (Map<String, Map<String, Double>>) comparison.get("byTag");
            deltas.forEach((tag, delta) -> out.append("| ").append(tag).append(" | ")
                    .append(number(delta.get("recallDeltaVsVector") * 100)).append(" | ")
                    .append(number(delta.get("recallDeltaVsRrf") * 100)).append(" | ")
                    .append(number(delta.get("mrrDeltaVsRrf"))).append(" | ")
                    .append(number(delta.get("ndcgDeltaVsRrf"))).append(" |\n"));
        }
        return out.toString();
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.4f", value); }
    private static String percent(double value) { return String.format(Locale.ROOT, "%.2f%%", value * 100); }
}
