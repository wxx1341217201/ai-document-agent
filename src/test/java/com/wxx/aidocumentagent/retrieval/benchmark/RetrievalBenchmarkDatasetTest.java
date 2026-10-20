package com.wxx.aidocumentagent.retrieval.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalBenchmarkDatasetTest {

    private final Path file = Path.of("target", "retrieval-benchmark-dataset-test.jsonl");

    @AfterEach
    void removeTestFile() throws Exception {
        Files.deleteIfExists(file);
    }

    @Test
    void loadsScopedLabelsAndExplicitNoAnswerCases() throws Exception {
        Files.writeString(file, """
                {"id":"Q1","knowledgeBaseId":2,"question":"golden key","answerable":true,"expectedChunkIds":[252],"tags":["exact"]}
                {"id":"Q2","knowledgeBaseId":2,"question":"unknown","answerable":false,"expectedChunkIds":[],"tags":["unanswerable"]}
                """);

        RetrievalBenchmarkDataset dataset = RetrievalBenchmarkDataset.load(file, new JsonMapper());

        assertThat(dataset.knowledgeBaseId()).isEqualTo(2);
        assertThat(dataset.cases()).hasSize(2);
        assertThat(dataset.cases().getFirst().expectedChunkIds()).containsExactly(252L);
    }

    @Test
    void rejectsMissingLabelsAndMixedKnowledgeBases() throws Exception {
        Files.writeString(file, """
                {"id":"Q1","knowledgeBaseId":2,"question":"golden key","answerable":true,"expectedChunkIds":[],"tags":["exact"]}
                """);
        assertThatThrownBy(() -> RetrievalBenchmarkDataset.load(file, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedChunkIds");

        Files.writeString(file, """
                {"id":"Q1","knowledgeBaseId":2,"question":"golden key","answerable":true,"expectedChunkIds":[252],"tags":["exact"]}
                {"id":"Q2","knowledgeBaseId":3,"question":"rabbit","answerable":true,"expectedChunkIds":[999],"tags":["semantic"]}
                """);
        assertThatThrownBy(() -> RetrievalBenchmarkDataset.load(file, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mixes knowledge bases");

        Files.writeString(file, """
                {"id":"Q1","knowledgeBaseId":2,"question":"golden key","answerable":true,"expectedChunkIds":[252],"tags":["exact","exact"]}
                """);
        assertThatThrownBy(() -> RetrievalBenchmarkDataset.load(file, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tags");
    }
}
