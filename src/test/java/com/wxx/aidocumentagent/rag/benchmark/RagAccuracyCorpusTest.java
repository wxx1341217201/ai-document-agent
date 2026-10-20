package com.wxx.aidocumentagent.rag.benchmark;

import java.nio.file.Path;
import java.util.List;

import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagAccuracyCorpusTest {

    private final JsonMapper mapper = new JsonMapper();
    @TempDir
    Path directory;

    @Test
    void checkedInLabelsMatchEverySupportingChunkInPortableCorpus() throws Exception {
        var corpus = RagAccuracyCorpus.load(Path.of("benchmarks/rag/source-corpus.jsonl"), 2, mapper);
        var dataset = RagAccuracyDataset.load(Path.of("benchmarks/rag/qa-dataset.jsonl"), mapper);
        corpus.validateLabels(dataset);
        assertThat(corpus.chunks()).hasSize(249);
        assertThat(dataset.samples().stream().flatMap(sample -> sample.requiredFacts().stream()))
                .anySatisfy(fact -> assertThat(fact.supportingChunkIds()).hasSizeGreaterThan(1));
        Path snapshot = directory.resolve("corpus.jsonl");
        corpus.write(snapshot, mapper);
        assertThat(RagAccuracyCorpus.load(snapshot, 2, mapper)).isEqualTo(corpus);
        assertThatThrownBy(() -> corpus.write(snapshot, mapper)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test
    void oneSupportingQuoteCannotAuthorizeAnUnrelatedChunk() {
        var first = chunk(1, 10, "golden key");
        var unrelated = chunk(2, 10, "unrelated text");
        var fact = new RagAccuracyDataset.RequiredFact("F1", "golden key", List.of("golden key"), List.of(1L, 2L), "golden key");
        var sample = new RagAccuracyDataset.Sample("S1", 2, "key?", RagAccuracyDataset.QuestionType.SINGLE_DOCUMENT,
                true, "golden key", List.of(fact), 1.0, List.of(1L, 2L), List.of(), "draft");
        var corpus = new RagAccuracyCorpus(List.of(first, unrelated), 2);
        assertThatThrownBy(() -> corpus.validateLabels(new RagAccuracyDataset(List.of(sample), 2)))
                .hasMessage("SOURCE_QUOTE_NOT_IN_LABELED_CHUNK");
    }

    @Test
    void validatesScopeDuplicatesAndActualTruncatedEvidence() {
        var corpus = new RagAccuracyCorpus(List.of(chunk(1, 10, "golden key on a table")), 2);
        var citation = new RagCitationResponse("C1", 10, "source.txt", 1, null, null, "golden key");
        var evidence = new RagEvidenceChunk(2, citation, "golden key on", BenchmarkText.sha256("golden key on a table"));
        corpus.verifyEvidence(2, List.of(evidence));
        assertThatThrownBy(() -> corpus.verifyEvidence(3, List.of(evidence))).hasMessage("UNVERIFIED_CURRENT_CONTEXT");
        assertThatThrownBy(() -> corpus.verifyEvidence(2, List.of(new RagEvidenceChunk(2, citation,
                "fabricated context", evidence.sourceContentSha256())))).hasMessage("UNVERIFIED_CURRENT_CONTEXT");
        assertThatThrownBy(() -> new RagAccuracyCorpus(List.of(chunk(1, 10, "x"), chunk(1, 10, "y")), 2))
                .hasMessage("FOREIGN_OR_INVALID_PERSISTED_CHUNK");
    }

    private RagAccuracyCorpus.Chunk chunk(long id, long document, String content) {
        return new RagAccuracyCorpus.Chunk(id, 2L, document, 0, content, null, null);
    }
}
