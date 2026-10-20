package com.wxx.aidocumentagent.retrieval.benchmark;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalBenchmarkCorpusTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void committedDatasetCoversEveryRequestedBucketWithoutInventingHumanProvenance() throws Exception {
        var dataset = RetrievalBenchmarkDataset.load(Path.of("benchmarks/retrieval/retrieval-dataset.jsonl"), mapper);
        dataset.requireBenchmarkCoverage();
        assertThat(dataset.cases()).hasSize(248);
        assertThat(dataset.cases().stream().filter(c -> c.answerable())).hasSize(234);
        var supplemental = RetrievalBenchmarkCorpus.load(Path.of("benchmarks/retrieval/supplemental-corpus.jsonl"), mapper);
        var technical = new RetrievalBenchmarkDataset(dataset.cases().stream()
                .filter(c -> c.tags().contains("technical")).toList(), 2);
        supplemental.validateLabels(technical);
        assertThat(RetrievalBenchmarkLabelReview.assess(null, dataset, "dataset", "corpus", mapper))
                .containsEntry("status", "PENDING_HUMAN_REVIEW").containsEntry("verified", 0L);
    }

    @Test
    void checksKnowledgeBaseAndActualCrossDocumentLabels() {
        var corpus = new RetrievalBenchmarkCorpus(List.of(chunk(1, 2, 7, "one"), chunk(2, 2, 7, "two")));
        var dataset = new RetrievalBenchmarkDataset(List.of(new RetrievalBenchmarkDataset.Case("cross", 2, "question",
                true, List.of(1L, 2L), List.of("cross_context", "cross_document"))), 2);
        assertThatThrownBy(() -> corpus.validateLabels(dataset)).hasMessageContaining("different documents");
        assertThatThrownBy(() -> new RetrievalBenchmarkCorpus(List.of(chunk(1, 3, 7, "one"))).validateLabels(
                new RetrievalBenchmarkDataset(List.of(new RetrievalBenchmarkDataset.Case("q", 2, "question", true,
                        List.of(1L), List.of("exact"))), 2))).hasMessageContaining("boundary");
    }

    @Test
    void fingerprintsContentAndDocumentMetadataAndRejectsDuplicateIds() {
        var first = new RetrievalBenchmarkCorpus(List.of(chunk(1, 2, 7, "one")));
        var changedContent = new RetrievalBenchmarkCorpus(List.of(chunk(1, 2, 7, "different")));
        var changedDocument = new RetrievalBenchmarkCorpus(List.of(chunk(1, 2, 8, "one")));
        assertThat(first.fingerprint(mapper)).isNotEqualTo(changedContent.fingerprint(mapper))
                .isNotEqualTo(changedDocument.fingerprint(mapper));
        assertThatThrownBy(() -> new RetrievalBenchmarkCorpus(List.of(chunk(1, 2, 7, "one"), chunk(1, 2, 8, "two"))))
                .hasMessageContaining("duplicate");
    }

    private static RetrievalBenchmarkCorpus.Chunk chunk(long id, long kb, long doc, String content) {
        return new RetrievalBenchmarkCorpus.Chunk(id, kb, doc, 0, content, "title", "section", null, null, "test");
    }
}
