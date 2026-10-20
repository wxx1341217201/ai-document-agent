package com.wxx.aidocumentagent.rag.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.wxx.aidocumentagent.rag.application.RagConversationTurn;
import tools.jackson.databind.ObjectMapper;

/** 标签在调用检索与模型之前固定；不允许使用模型答案回填 referenceAnswer。 */
public record RagAccuracyDataset(List<Sample> samples, long knowledgeBaseId) {

    public static final String LABEL_PROVENANCE = "assistant_curated_pending_human_review";

    public RagAccuracyDataset {
        samples = List.copyOf(samples);
        if (samples.isEmpty() || knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("Dataset and knowledgeBaseId are required");
        }
    }

    public static RagAccuracyDataset load(Path path, ObjectMapper mapper) throws IOException {
        List<Sample> samples = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long knowledgeBaseId = 0;
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            Sample sample;
            try {
                sample = mapper.readValue(line, Sample.class);
            }
            catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid QA JSONL at line " + lineNumber, exception);
            }
            if (sample == null || !ids.add(sample.id())) {
                throw new IllegalArgumentException("Null or duplicate QA sample at line " + lineNumber);
            }
            if (knowledgeBaseId == 0) {
                knowledgeBaseId = sample.knowledgeBaseId();
            }
            if (knowledgeBaseId != sample.knowledgeBaseId()) {
                throw new IllegalArgumentException("Dataset mixes knowledge bases at line " + lineNumber);
            }
            samples.add(sample);
        }
        return new RagAccuracyDataset(samples, knowledgeBaseId);
    }

    public void requireBenchmarkCoverage() {
        Set<QuestionType> types = new HashSet<>();
        samples.forEach(sample -> types.add(sample.type()));
        if (samples.size() < 200 || types.size() != QuestionType.values().length) {
            throw new IllegalArgumentException("Benchmark requires >=200 QA and all five question types");
        }
    }

    public enum QuestionType { SINGLE_DOCUMENT, MULTI_CHUNK, CROSS_DOCUMENT, MULTI_TURN, UNANSWERABLE }

    public record Sample(String id, long knowledgeBaseId, String question, QuestionType type, Boolean answerable,
                         String referenceAnswer, List<RequiredFact> requiredFacts, Double factCoverageThreshold,
                         List<Long> acceptableChunkIds, List<RagConversationTurn> history, String labelProvenance) {

        public Sample {
            requiredFacts = requiredFacts == null ? null : List.copyOf(requiredFacts);
            acceptableChunkIds = acceptableChunkIds == null ? null : List.copyOf(acceptableChunkIds);
            history = history == null ? List.of() : List.copyOf(history);
            if (blank(id) || knowledgeBaseId <= 0 || blank(question) || type == null || answerable == null
                    || blank(referenceAnswer) || blank(labelProvenance) || requiredFacts == null
                    || acceptableChunkIds == null || factCoverageThreshold == null
                    || !Double.isFinite(factCoverageThreshold) || factCoverageThreshold <= 0
                    || factCoverageThreshold > 1 || history.size() > 20) {
                throw new IllegalArgumentException("Incomplete QA labels: " + id);
            }
            validateIds(acceptableChunkIds, "acceptableChunkIds");
            if (answerable != !requiredFacts.isEmpty() || answerable != !acceptableChunkIds.isEmpty()
                    || (type == QuestionType.UNANSWERABLE) == answerable
                    || (type == QuestionType.MULTI_TURN && history.isEmpty())) {
                throw new IllegalArgumentException("QA answerability/type/evidence mismatch: " + id);
            }
            Set<String> factIds = new HashSet<>();
            for (RequiredFact fact : requiredFacts) {
                if (!factIds.add(fact.id()) || !acceptableChunkIds.containsAll(fact.supportingChunkIds())) {
                    throw new IllegalArgumentException("Duplicate fact or unsupported citation range: " + id);
                }
            }
            if ((type == QuestionType.MULTI_CHUNK || type == QuestionType.CROSS_DOCUMENT)
                    && (requiredFacts.size() < 2 || acceptableChunkIds.size() < 2)) {
                throw new IllegalArgumentException("Synthesis/comparison requires multiple facts and chunks: " + id);
            }
        }
    }

    public record RequiredFact(String id, String statement, List<String> acceptedPhrases,
                               List<Long> supportingChunkIds, String sourceQuote) {

        public RequiredFact {
            acceptedPhrases = acceptedPhrases == null ? null : List.copyOf(acceptedPhrases);
            supportingChunkIds = supportingChunkIds == null ? null : List.copyOf(supportingChunkIds);
            if (blank(id) || blank(statement) || blank(sourceQuote) || acceptedPhrases == null
                    || acceptedPhrases.isEmpty() || acceptedPhrases.stream().anyMatch(RagAccuracyDataset::blank)
                    || supportingChunkIds == null || supportingChunkIds.isEmpty()) {
                throw new IllegalArgumentException("Required facts need a statement, aliases and source evidence");
            }
            validateIds(supportingChunkIds, "supportingChunkIds");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateIds(List<Long> ids, String name) {
        if (ids.stream().anyMatch(id -> id == null || id <= 0) || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Invalid or duplicate " + name);
        }
    }
}
