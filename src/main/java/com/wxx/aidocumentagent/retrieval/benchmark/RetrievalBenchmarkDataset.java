package com.wxx.aidocumentagent.retrieval.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** JSONL labels are fixed before retrieval; predictions must never be used to fill missing labels. */
public record RetrievalBenchmarkDataset(List<Case> cases, long knowledgeBaseId) {

    public RetrievalBenchmarkDataset {
        cases = List.copyOf(cases);
        if (cases.isEmpty() || knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("Benchmark dataset and knowledgeBaseId are required");
        }
    }

    public static RetrievalBenchmarkDataset load(Path path, ObjectMapper mapper) throws IOException {
        List<Case> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long knowledgeBaseId = 0;
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            Case item;
            try {
                var object = mapper.readTree(line);
                for (String field : List.of("id", "knowledgeBaseId", "question", "answerable", "expectedChunkIds", "tags")) {
                    if (object == null || !object.has(field) || object.get(field).isNull()) {
                        throw new IllegalArgumentException("Missing " + field + " at line " + lineNumber);
                    }
                }
                item = mapper.readValue(line, Case.class);
            }
            catch (JacksonException exception) {
                throw new IllegalArgumentException("Invalid JSONL at line " + lineNumber, exception);
            }
            if (item == null) {
                throw new IllegalArgumentException("Null case at line " + lineNumber);
            }
            item.validate();
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("Duplicate benchmark case id: " + item.id());
            }
            if (knowledgeBaseId == 0) {
                knowledgeBaseId = item.knowledgeBaseId();
            }
            else if (item.knowledgeBaseId() != knowledgeBaseId) {
                throw new IllegalArgumentException("Dataset mixes knowledge bases at line " + lineNumber);
            }
            cases.add(item);
        }
        return new RetrievalBenchmarkDataset(cases, knowledgeBaseId);
    }

    public void requireBenchmarkCoverage() {
        Set<String> tags = new HashSet<>();
        cases.forEach(item -> tags.addAll(item.tags()));
        Set<String> required = Set.of("exact", "semantic", "chinese_synonym", "cross_context", "cross_document",
                "distractor", "unanswerable", "error_code", "identifier");
        if (cases.size() < 200 || !tags.containsAll(required) || cases.stream().noneMatch(Case::answerable)) {
            throw new IllegalArgumentException("Benchmark requires >=200 questions and tags " + required);
        }
    }

    public record Case(String id, long knowledgeBaseId, String question, boolean answerable,
                       List<Long> expectedChunkIds, List<String> tags) {

        public Case {
            expectedChunkIds = expectedChunkIds == null ? null : List.copyOf(expectedChunkIds);
            tags = tags == null ? null : List.copyOf(tags);
        }

        void validate() {
            if (id == null || id.isBlank() || knowledgeBaseId <= 0 || question == null || question.isBlank()) {
                throw new IllegalArgumentException("Each case requires id, knowledgeBaseId and question");
            }
            if (expectedChunkIds == null || tags == null || tags.isEmpty()
                    || tags.stream().anyMatch(tag -> tag == null || tag.isBlank())
                    || new HashSet<>(tags).size() != tags.size()) {
                throw new IllegalArgumentException("Each case requires expectedChunkIds and tags: " + id);
            }
            if (answerable != !expectedChunkIds.isEmpty()) {
                throw new IllegalArgumentException("Only unanswerable cases may have no expectedChunkIds: " + id);
            }
            if (expectedChunkIds.stream().anyMatch(chunkId -> chunkId == null || chunkId <= 0)
                    || new HashSet<>(expectedChunkIds).size() != expectedChunkIds.size()) {
                throw new IllegalArgumentException("Invalid or duplicate expectedChunkIds: " + id);
            }
            if (!answerable && !tags.contains("unanswerable")) {
                throw new IllegalArgumentException("Unanswerable case needs unanswerable tag: " + id);
            }
            if (tags.contains("all") || (answerable && tags.contains("unanswerable"))) {
                throw new IllegalArgumentException("Reserved or conflicting tag: " + id);
            }
        }
    }
}
