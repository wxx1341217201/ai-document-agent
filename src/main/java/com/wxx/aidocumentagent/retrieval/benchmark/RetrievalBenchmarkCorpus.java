package com.wxx.aidocumentagent.retrieval.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/** Frozen, scoped retrieval evidence. Fixture IDs need not be inserted into application tables. */
public record RetrievalBenchmarkCorpus(List<Chunk> chunks) {
    public RetrievalBenchmarkCorpus {
        chunks = List.copyOf(chunks);
        var ids = new HashSet<Long>();
        for (Chunk chunk : chunks) {
            if (chunk.chunkId() <= 0 || chunk.knowledgeBaseId() <= 0 || chunk.documentId() <= 0
                    || chunk.chunkIndex() < 0 || chunk.content() == null || chunk.content().isBlank()
                    || !ids.add(chunk.chunkId())) {
                throw new IllegalArgumentException("Invalid or duplicate corpus chunk");
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Benchmark corpus is empty");
        }
    }

    public static RetrievalBenchmarkCorpus load(Path path, ObjectMapper mapper) throws IOException {
        var chunks = new java.util.ArrayList<Chunk>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                chunks.add(mapper.readValue(line, Chunk.class));
            }
        }
        return new RetrievalBenchmarkCorpus(chunks);
    }

    public Map<Long, Chunk> byId() {
        Map<Long, Chunk> indexed = new LinkedHashMap<>();
        chunks.forEach(chunk -> indexed.put(chunk.chunkId(), chunk));
        return indexed;
    }

    public void validateLabels(RetrievalBenchmarkDataset dataset) {
        Map<Long, Chunk> indexed = byId();
        if (chunks.stream().anyMatch(chunk -> chunk.knowledgeBaseId() != dataset.knowledgeBaseId())) {
            throw new IllegalArgumentException("Corpus crosses knowledge base boundary");
        }
        for (var item : dataset.cases()) {
            if (!indexed.keySet().containsAll(item.expectedChunkIds())) {
                throw new IllegalArgumentException("Some expectedChunkIds are absent from the specified knowledge base: "
                        + item.id());
            }
            if (item.tags().contains("cross_context") && item.expectedChunkIds().size() < 2) {
                throw new IllegalArgumentException("Cross-context case requires multiple labels: " + item.id());
            }
            if (item.tags().contains("cross_document") && item.expectedChunkIds().stream()
                    .map(id -> indexed.get(id).documentId()).distinct().count() < 2) {
                throw new IllegalArgumentException("Cross-document labels must belong to different documents: " + item.id());
            }
        }
    }

    public String fingerprint(ObjectMapper mapper) {
        return RetrievalBenchmarkSupport.sha256(mapper.writeValueAsBytes(chunks.stream()
                .sorted(java.util.Comparator.comparingLong(Chunk::chunkId)).toList()));
    }

    public record Chunk(long chunkId, long knowledgeBaseId, long documentId, int chunkIndex, String content,
                        String title, String sectionTitle, Integer pageFrom, Integer pageTo, String sourceReference) {
        public String contentHash() {
            return RetrievalBenchmarkSupport.sha256(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
