package com.wxx.aidocumentagent.rag.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagAccuracyDatasetTest {

    @TempDir
    Path directory;
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void checkedInDraftHas200QaAllTypesAtomicFactsAndExplicitRefusalLabels() throws Exception {
        var dataset = RagAccuracyDataset.load(Path.of("benchmarks/rag/qa-dataset.jsonl"), mapper);
        dataset.requireBenchmarkCoverage();
        assertThat(dataset.knowledgeBaseId()).isEqualTo(2);
        assertThat(dataset.samples()).hasSize(200);
        assertThat(dataset.samples().stream().filter(RagAccuracyDataset.Sample::answerable)).hasSize(190);
        assertThat(dataset.samples().stream().filter(sample -> sample.type() == RagAccuracyDataset.QuestionType.MULTI_TURN))
                .allSatisfy(sample -> assertThat(sample.history()).hasSize(2));
        assertThat(dataset.samples().getFirst().requiredFacts()).hasSize(2);
        assertThat(dataset.samples().stream().filter(sample -> !sample.answerable())).allSatisfy(sample -> {
            assertThat(sample.acceptableChunkIds()).isEmpty();
            assertThat(sample.referenceAnswer()).contains("我不知道");
        });
    }

    @Test
    void rejectsMissingAnswerableThresholdAndReferenceInsteadOfDefaultingLabels() throws Exception {
        String valid = mapper.writeValueAsString(sample("S1", 2));
        for (String bad : List.of(valid.replace("\"answerable\":true,", ""),
                valid.replace("\"referenceAnswer\":\"golden key\"", "\"referenceAnswer\":\"\""),
                valid.replace("\"factCoverageThreshold\":1.0", "\"factCoverageThreshold\":0.0"))) {
            Path file = directory.resolve("bad.jsonl");
            Files.writeString(file, bad);
            assertThatThrownBy(() -> RagAccuracyDataset.load(file, mapper)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMixedKnowledgeBasesAndDuplicateCaseIds() throws Exception {
        Path file = directory.resolve("bad.jsonl");
        Files.writeString(file, mapper.writeValueAsString(sample("S1", 2)) + "\n"
                + mapper.writeValueAsString(sample("S2", 3)));
        assertThatThrownBy(() -> RagAccuracyDataset.load(file, mapper)).hasMessageContaining("mixes knowledge bases");
        Files.writeString(file, mapper.writeValueAsString(sample("S1", 2)) + "\n"
                + mapper.writeValueAsString(sample("S1", 2)));
        assertThatThrownBy(() -> RagAccuracyDataset.load(file, mapper)).hasMessageContaining("duplicate");
    }

    @Test
    void rejectsUnsupportedFactEvidenceAndMultiTurnWithoutHistory() {
        var fact = new RagAccuracyDataset.RequiredFact("F1", "golden key", List.of("golden key"), List.of(99L), "golden key");
        assertThatThrownBy(() -> new RagAccuracyDataset.Sample("S1", 2, "key?",
                RagAccuracyDataset.QuestionType.SINGLE_DOCUMENT, true, "golden key", List.of(fact), 1.0,
                List.of(1L), List.of(), "draft")).hasMessageContaining("unsupported citation range");
        var base = sample("S1", 2);
        assertThatThrownBy(() -> new RagAccuracyDataset.Sample(base.id(), 2, base.question(),
                RagAccuracyDataset.QuestionType.MULTI_TURN, true, base.referenceAnswer(), base.requiredFacts(), 1.0,
                base.acceptableChunkIds(), List.of(), "draft")).hasMessageContaining("mismatch");
        assertThatThrownBy(() -> new RagAccuracyDataset(List.of(base), 2).requireBenchmarkCoverage())
                .hasMessageContaining(">=200");
    }

    static RagAccuracyDataset.Sample sample(String id, long kb) {
        return new RagAccuracyDataset.Sample(id, kb, "What is on the table?",
                RagAccuracyDataset.QuestionType.SINGLE_DOCUMENT, true, "golden key",
                List.of(new RagAccuracyDataset.RequiredFact("F1", "golden key", List.of("golden key", "金钥匙"),
                        List.of(1L), "golden key")), 1.0, List.of(1L), List.of(), "draft");
    }
}
