package com.wxx.aidocumentagent.retrieval.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Binary relevance: Recall@5 counts questions with at least one relevant chunk in the first five. */
public final class RetrievalBenchmarkMetrics {

    private RetrievalBenchmarkMetrics() {
    }

    public static Map<String, Score> score(List<RetrievalBenchmarkDataset.Case> cases,
                                            Map<String, List<Long>> predictions) {
        Map<String, Accumulator> buckets = new LinkedHashMap<>();
        buckets.put("all", new Accumulator());
        for (RetrievalBenchmarkDataset.Case item : cases) {
            if (!predictions.containsKey(item.id())) {
                throw new IllegalArgumentException("Missing prediction for " + item.id());
            }
            List<Long> topFive = predictions.get(item.id()).stream().distinct().limit(5).toList();
            buckets.get("all").add(item, topFive);
            for (String tag : item.tags()) {
                buckets.computeIfAbsent(tag, ignored -> new Accumulator()).add(item, topFive);
            }
        }
        Map<String, Score> scores = new LinkedHashMap<>();
        buckets.forEach((tag, accumulator) -> scores.put(tag, accumulator.finish()));
        return scores;
    }

    public record Score(int answerable, int hitsAt5, double recallAt5, double mrrAt5, double ndcgAt5,
                        int unanswerable, int emptyResultsForUnanswerable) {
    }

    public static Map<String, Double> latencyMillis(List<Long> successfulNanos) {
        if (successfulNanos.isEmpty()) {
            return Map.of();
        }
        List<Long> sorted = new ArrayList<>(successfulNanos);
        sorted.sort(Long::compareTo);
        double sum = sorted.stream().mapToDouble(Long::doubleValue).sum();
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("p50", percentile(sorted, 0.50));
        result.put("p95", percentile(sorted, 0.95));
        result.put("p99", percentile(sorted, 0.99));
        result.put("average", sum / sorted.size() / 1_000_000D);
        result.put("max", sorted.getLast() / 1_000_000D);
        return result;
    }

    private static double percentile(List<Long> sorted, double quantile) {
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000D;
    }

    private static final class Accumulator {

        private int answerable;
        private int hits;
        private double reciprocalRankSum;
        private double ndcgSum;
        private int unanswerable;
        private int emptyUnanswerable;

        void add(RetrievalBenchmarkDataset.Case item, List<Long> topFive) {
            if (!item.answerable()) {
                unanswerable++;
                if (topFive.isEmpty()) {
                    emptyUnanswerable++;
                }
                return;
            }
            answerable++;
            Set<Long> relevant = Set.copyOf(item.expectedChunkIds());
            double dcg = 0;
            int firstRelevantRank = 0;
            for (int index = 0; index < topFive.size(); index++) {
                if (relevant.contains(topFive.get(index))) {
                    if (firstRelevantRank == 0) {
                        firstRelevantRank = index + 1;
                    }
                    dcg += 1D / log2(index + 2);
                }
            }
            if (firstRelevantRank > 0) {
                hits++;
                reciprocalRankSum += 1D / firstRelevantRank;
            }
            double idealDcg = 0;
            for (int index = 0; index < Math.min(5, relevant.size()); index++) {
                idealDcg += 1D / log2(index + 2);
            }
            ndcgSum += dcg / idealDcg;
        }

        Score finish() {
            return new Score(answerable, hits, answerable == 0 ? 0 : hits / (double) answerable,
                    answerable == 0 ? 0 : reciprocalRankSum / answerable,
                    answerable == 0 ? 0 : ndcgSum / answerable, unanswerable, emptyUnanswerable);
        }

        private double log2(double value) {
            return Math.log(value) / Math.log(2);
        }
    }
}
