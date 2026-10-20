package com.wxx.aidocumentagent.retrieval.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/** Human review is bound to both frozen questions and source content, never inferred from retrieval scores. */
final class RetrievalBenchmarkLabelReview {
    private RetrievalBenchmarkLabelReview() { }

    static Map<String, Object> assess(Path reviews, RetrievalBenchmarkDataset dataset, String datasetHash,
                                      String corpusHash, ObjectMapper mapper) throws IOException {
        Map<String, Review> indexed = new HashMap<>();
        var cases = new HashMap<String, RetrievalBenchmarkDataset.Case>();
        dataset.cases().forEach(item -> cases.put(item.id(), item));
        if (reviews != null) {
            for (String line : Files.readAllLines(reviews, StandardCharsets.UTF_8)) {
                if (line.isBlank()) { continue; }
                Review review = mapper.readValue(line, Review.class);
                var item = cases.get(review.caseId());
                if (item == null || indexed.putIfAbsent(review.caseId(), review) != null
                        || !datasetHash.equals(review.datasetSha256()) || !corpusHash.equals(review.corpusFingerprint())
                        || !caseHash(item, mapper).equals(review.caseSha256())) {
                    throw new IllegalArgumentException("Unknown, duplicate or stale label review");
                }
                if (Boolean.FALSE.equals(review.expectedLabelsVerified())) {
                    throw new IllegalArgumentException("Human review rejected labels: " + review.caseId());
                }
                if (Boolean.TRUE.equals(review.expectedLabelsVerified())) {
                    if (review.reviewer() == null || review.reviewer().isBlank()
                            || review.reviewer().toLowerCase(java.util.Locale.ROOT).contains("assistant")
                            || review.reviewedAt() == null
                            || Instant.parse(review.reviewedAt()).isAfter(Instant.now().plusSeconds(300))) {
                        throw new IllegalArgumentException("Label review requires a human reviewer and timestamp");
                    }
                }
            }
        }
        long verified = indexed.values().stream().filter(r -> Boolean.TRUE.equals(r.expectedLabelsVerified())).count();
        return Map.of("status", verified == dataset.cases().size() ? "HUMAN_REVIEWED" : "PENDING_HUMAN_REVIEW",
                "verified", verified, "total", dataset.cases().size());
    }

    static String template(RetrievalBenchmarkDataset dataset, String datasetHash, String corpusHash, ObjectMapper mapper) {
        StringBuilder output = new StringBuilder();
        for (var item : dataset.cases()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("caseId", item.id());
            row.put("datasetSha256", datasetHash);
            row.put("corpusFingerprint", corpusHash);
            row.put("caseSha256", caseHash(item, mapper));
            row.put("reviewer", "");
            row.put("reviewedAt", null);
            row.put("expectedLabelsVerified", null);
            row.put("notes", "");
            output.append(mapper.writeValueAsString(row)).append('\n');
        }
        return output.toString();
    }

    private static String caseHash(RetrievalBenchmarkDataset.Case item, ObjectMapper mapper) {
        return RetrievalBenchmarkSupport.sha256(mapper.writeValueAsBytes(item));
    }

    record Review(String caseId, String datasetSha256, String corpusFingerprint, String caseSha256,
                  String reviewer, String reviewedAt, Boolean expectedLabelsVerified, String notes) { }
}
