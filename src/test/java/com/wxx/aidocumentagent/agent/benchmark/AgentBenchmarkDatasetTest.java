package com.wxx.aidocumentagent.agent.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.wxx.aidocumentagent.agent.AgentMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBenchmarkDatasetTest {
    private final JsonMapper mapper = new JsonMapper();
    @TempDir Path temp;
    @Test void fixed100TasksCoverAllModesAndRealMultiTurnScripts() throws Exception {
        var dataset = AgentBenchmarkDataset.load(Path.of("benchmarks/agent/agent-tasks.jsonl"), mapper);
        dataset.requireBenchmarkCoverage();
        assertThat(dataset.tasks()).hasSize(100);
        assertThat(dataset.knowledgeBaseId()).isEqualTo(2);
        assertThat(dataset.tasks().stream().filter(t -> t.mode() == AgentMode.RETRIEVAL_FIRST)).hasSize(30);
        assertThat(dataset.tasks().stream().filter(t -> t.mode() == AgentMode.REACT)).hasSize(35);
        assertThat(dataset.tasks().stream().filter(t -> t.mode() == AgentMode.PLAN_EXECUTE_REFLECT)).hasSize(35);
        assertThat(dataset.tasks().stream().filter(t -> t.turns().size() > 1)).hasSize(19);
        assertThat(dataset.tasks().stream().filter(t -> t.fault() != null)).hasSize(10);
        assertThat(dataset.tasks()).allMatch(t -> t.labelProvenance().equals("assistant_curated_pending_human_review"));
    }
    @Test void rejectsMissingLabelsUnknownGuardsAndMixedScope() throws Exception {
        var task = AgentBenchmarkFixtures.task(AgentMode.REACT);
        for (var mutation : Map.of("mustHaveValidCitation", "null", "requiredFacts", "[]", "forbiddenBehaviors", "[\"madeUpGuard\"]").entrySet()) {
            var node = (ObjectNode) mapper.readTree(mapper.writeValueAsString(task));
            node.set(mutation.getKey(), mapper.readTree(mutation.getValue()));
            Path path = temp.resolve(mutation.getKey() + ".jsonl");
            Files.writeString(path, mapper.writeValueAsString(node));
            assertThatThrownBy(() -> AgentBenchmarkDataset.load(path, mapper)).isInstanceOf(IllegalArgumentException.class);
        }
        var foreign = (ObjectNode) mapper.readTree(mapper.writeValueAsString(task));
        foreign.put("caseId", "UNIT-002"); foreign.put("knowledgeBaseId", 99);
        Path path = temp.resolve("mixed.jsonl");
        Files.writeString(path, mapper.writeValueAsString(task) + "\n" + mapper.writeValueAsString(foreign));
        assertThatThrownBy(() -> AgentBenchmarkDataset.load(path, mapper)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mixed-scope");
    }
    @Test void duplicateCasesAndPartialCoverageCannotShrinkTheBenchmark() throws Exception {
        String line = mapper.writeValueAsString(AgentBenchmarkFixtures.task(AgentMode.REACT));
        Path path = temp.resolve("duplicate.jsonl"); Files.writeString(path, line + "\n" + line);
        assertThatThrownBy(() -> AgentBenchmarkDataset.load(path, mapper)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(path, line);
        assertThatThrownBy(() -> AgentBenchmarkDataset.load(path, mapper).requireBenchmarkCoverage()).hasMessageContaining(">=100");
    }
}
