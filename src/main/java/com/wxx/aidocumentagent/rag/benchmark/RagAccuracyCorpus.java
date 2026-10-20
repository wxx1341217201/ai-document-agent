package com.wxx.aidocumentagent.rag.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import com.wxx.aidocumentagent.rag.benchmark.RagAccuracyDataset.QuestionType;
import tools.jackson.databind.ObjectMapper;

/** 冻结整个知识库正文，供无答案标签复核、离线证据核验和运行前后指纹比较。 */
public record RagAccuracyCorpus(List<Chunk> chunks, long knowledgeBaseId) {

    public RagAccuracyCorpus {
        chunks = chunks.stream().sorted(Comparator.comparingLong(Chunk::chunkId)).toList();
        if (knowledgeBaseId <= 0 || chunks.isEmpty()
                || chunks.stream().anyMatch(chunk -> chunk.knowledgeBaseId() != knowledgeBaseId)
                || chunks.stream().map(Chunk::chunkId).distinct().count() != chunks.size()) {
            throw new IllegalArgumentException("FOREIGN_OR_INVALID_PERSISTED_CHUNK");
        }
    }

    public static RagAccuracyCorpus capture(long knowledgeBaseId, List<DocumentChunk> sources) {
        return new RagAccuracyCorpus(sources.stream().map(source -> new Chunk(source.getId(),
                source.getKnowledgeBaseId(), source.getDocumentId(), source.getChunkIndex(), source.getContent(),
                source.getPageFrom(), source.getPageTo())).toList(), knowledgeBaseId);
    }

    public static RagAccuracyCorpus load(Path path, long knowledgeBaseId, ObjectMapper mapper) throws IOException {
        return new RagAccuracyCorpus(Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank()).map(line -> mapper.readValue(line, Chunk.class)).toList(), knowledgeBaseId);
    }

    public void write(Path path, ObjectMapper mapper) throws IOException {
        Files.writeString(path, String.join("\n", chunks.stream().map(mapper::writeValueAsString).toList()) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    public String fingerprint(ObjectMapper mapper) {
        return BenchmarkText.sha256(mapper.writeValueAsString(chunks));
    }

    public void validateLabels(RagAccuracyDataset dataset) {
        Map<Long, Chunk> persisted = byId();
        for (var sample : dataset.samples()) {
            if (sample.knowledgeBaseId() != knowledgeBaseId || !persisted.keySet().containsAll(sample.acceptableChunkIds())) {
                throw new IllegalArgumentException("MISSING_OR_FOREIGN_LABELED_CHUNK");
            }
            for (var fact : sample.requiredFacts()) {
                if (!fact.supportingChunkIds().stream().allMatch(id -> BenchmarkText.normalize(persisted.get(id).content())
                        .contains(BenchmarkText.normalize(fact.sourceQuote())))) {
                    throw new IllegalArgumentException("SOURCE_QUOTE_NOT_IN_LABELED_CHUNK");
                }
            }
            long documents = sample.acceptableChunkIds().stream().map(id -> persisted.get(id).documentId()).distinct().count();
            if (sample.type() == QuestionType.CROSS_DOCUMENT && documents < 2) {
                throw new IllegalArgumentException("CROSS_DOCUMENT_LABELS_NEED_DISTINCT_DOCUMENTS");
            }
            if (sample.type() == QuestionType.SINGLE_DOCUMENT && documents != 1) {
                throw new IllegalArgumentException("SINGLE_DOCUMENT_LABELS_NEED_ONE_DOCUMENT");
            }
        }
    }

    public void verifyEvidence(long requestedKnowledgeBaseId, List<RagEvidenceChunk> evidence) {
        Map<Long, Chunk> persisted = byId();
        for (var item : evidence) {
            Chunk source = persisted.get(item.citation().chunkId());
            if (requestedKnowledgeBaseId != knowledgeBaseId || item.knowledgeBaseId() != knowledgeBaseId || source == null
                    || source.documentId() != item.citation().documentId()
                    || !BenchmarkText.sha256(source.content()).equals(item.sourceContentSha256())
                    || !source.content().startsWith(item.contextText())
                    || item.citation().quote() == null || item.citation().quote().isBlank()
                    || !source.content().startsWith(item.citation().quote())
                    || !Objects.equals(source.pageFrom(), item.citation().pageFrom())
                    || !Objects.equals(source.pageTo(), item.citation().pageTo())) {
                throw new IllegalArgumentException("UNVERIFIED_CURRENT_CONTEXT");
            }
        }
    }

    private Map<Long, Chunk> byId() {
        Map<Long, Chunk> result = new HashMap<>();
        chunks.forEach(chunk -> result.put(chunk.chunkId(), chunk));
        return result;
    }

    public record Chunk(Long chunkId, Long knowledgeBaseId, Long documentId, int chunkIndex, String content,
                        Integer pageFrom, Integer pageTo) {
        public Chunk {
            if (chunkId == null || chunkId <= 0 || knowledgeBaseId == null || knowledgeBaseId <= 0
                    || documentId == null || documentId <= 0 || chunkIndex < 0 || content == null || content.isBlank()) {
                throw new IllegalArgumentException("FOREIGN_OR_INVALID_PERSISTED_CHUNK");
            }
        }
    }
}
