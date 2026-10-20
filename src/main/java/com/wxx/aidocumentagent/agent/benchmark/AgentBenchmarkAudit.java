package com.wxx.aidocumentagent.agent.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

/** Recompute all verdicts and metrics offline; a successful audit is not a successful Agent task. */
public final class AgentBenchmarkAudit {
    private AgentBenchmarkAudit() { }

    public static Map<String, Object> verify(Path source, ObjectMapper mapper) throws IOException {
        var original = mapper.readValue(Files.readString(source, StandardCharsets.UTF_8), AgentBenchmarkReport.class);
        if (!"m19c-report-v2".equals(original.schemaVersion())) {
            throw new IllegalArgumentException("Audit requires a v2 report with frozen corpus support");
        }
        var replay = AgentBenchmarkRunner.replay(source, null, mapper);
        if (original.configuration().containsKey("diagnosticJournalSha256")) {
            Path journal = Path.of(String.valueOf(original.configuration().get("diagnosticJournalPath")));
            if (!Files.exists(journal)) journal = source.toAbsolutePath().getParent().resolve(journal.getFileName());
            if (!Objects.equals(original.configuration().get("diagnosticJournalSha256"),
                    AgentBenchmarkText.sha256(Files.readAllBytes(journal)))) throw new IllegalArgumentException("Diagnostic journal was modified");
        }
        if (Boolean.FALSE.equals(original.configuration().get("applicationCodeStable")))
            throw new IllegalArgumentException("Application classes changed during the benchmark");
        if (!Objects.equals(original.overall(), replay.overall())
                || !Objects.equals(original.byMode(), replay.byMode())
                || !Objects.equals(original.byTag(), replay.byTag())
                || !Objects.equals(original.targetByMode(), replay.targetByMode())
                || !Objects.equals(original.status(), replay.status())
                || !Objects.equals(original.targetStatus(), replay.targetStatus())
                || !equivalent(original.cases(), replay.cases(), mapper)
                || !equivalent(original.failureDetails(), replay.failureDetails(), mapper)) {
            List<String> changed = new java.util.ArrayList<>();
            if (!Objects.equals(original.overall(), replay.overall())) changed.add("overall");
            if (!Objects.equals(original.byMode(), replay.byMode())) changed.add("byMode");
            if (!Objects.equals(original.byTag(), replay.byTag())) changed.add("byTag");
            if (!Objects.equals(original.targetByMode(), replay.targetByMode())) changed.add("targetByMode");
            if (!Objects.equals(original.status(), replay.status())) changed.add("status");
            if (!Objects.equals(original.targetStatus(), replay.targetStatus())) changed.add("targetStatus");
            if (!equivalent(original.cases(), replay.cases(), mapper)) changed.add("cases");
            if (!equivalent(original.failureDetails(), replay.failureDetails(), mapper)) changed.add("failureDetails");
            throw new IllegalArgumentException("Derived verdicts or metrics differ from replay: " + String.join(",", changed));
        }
        return Map.of("status", "PASSED", "runId", original.runId(), "reportSha256",
                AgentBenchmarkText.sha256(Files.readAllBytes(source)), "auditedAt", Instant.now().toString(),
                "tasks", original.cases().size(), "targetTaskSuccessRate", AgentBenchmarkReport.TARGET_SUCCESS_RATE,
                "checks", List.of("fixed_denominator", "dataset_and_run_binding", "frozen_corpus_and_evidence",
                        "actual_multi_turn_memory", "verdicts_and_failure_details", "overall_mode_and_tag_metrics"));
    }

    private static boolean equivalent(Object original, Object replay, ObjectMapper mapper) {
        return normalizeReasons(mapper.valueToTree(original), mapper).equals(normalizeReasons(mapper.valueToTree(replay), mapper));
    }

    private static tools.jackson.databind.JsonNode normalizeReasons(tools.jackson.databind.JsonNode node, ObjectMapper mapper) {
        // Map traversal can change the order of independent failures after JSON deserialization.
        // Preserve all values and array multiplicity; only failure-reason ordering is immaterial.
        if (node.isObject()) {
            var object = (tools.jackson.databind.node.ObjectNode) node;
            for (var entry : object.properties()) {
                if (entry.getKey().equals("failureReasons") && entry.getValue().isArray()) {
                    List<String> reasons = new java.util.ArrayList<>();
                    entry.getValue().forEach(reason -> reasons.add(reason.asString()));
                    object.set(entry.getKey(), mapper.valueToTree(reasons.stream().sorted().toList()));
                }
                else normalizeReasons(entry.getValue(), mapper);
            }
        }
        else if (node.isArray()) node.forEach(child -> normalizeReasons(child, mapper));
        return node;
    }

    static Path write(Path source, Path directory, ObjectMapper mapper) throws IOException {
        var result = verify(source, mapper);
        Files.createDirectories(directory);
        Path output = directory.resolve(source.getFileName().toString().replaceFirst("\\.json$", "")
                + "-audit-" + UUID.randomUUID().toString().substring(0, 8) + ".json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("M19C audit PASSED: " + output.toAbsolutePath());
        return output;
    }
}
