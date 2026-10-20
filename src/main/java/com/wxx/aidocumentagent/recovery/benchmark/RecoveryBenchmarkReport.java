package com.wxx.aidocumentagent.recovery.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkEvaluator.*;
import static com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkObservation.*;

public record RecoveryBenchmarkReport(String schemaVersion, String runId, String createdAt, long seed,
                                     String planSha256, Map<String, Object> environment, List<String> limitations,
                                     Summary overall, Map<String, Summary> byFault, String targetStatus, List<Case> cases) {
    public record Case(RecoveryBenchmarkPlan.Sample sample, boolean attempted, boolean faultVerified,
                       Double recoveryMillis, Snapshot before, Snapshot after, Execution checkpoint,
                       Execution recoveredExecution, Map<String, Object> faultEvidence, Evaluation evaluation) {
        public Case {
            faultEvidence = Map.copyOf(faultEvidence);
            if ((faultVerified && !attempted) || (attempted && (recoveryMillis == null || !Double.isFinite(recoveryMillis) || recoveryMillis < 0))
                    || (!attempted && recoveryMillis != null) || (evaluation.outcome() == Outcome.NOT_RUN && attempted)
                    || (List.of(Outcome.RECOVERED, Outcome.SESSION_LOST).contains(evaluation.outcome()) && (!attempted || !faultVerified || before == null))
                    || (evaluation.outcome() == Outcome.RECOVERED && after == null)) throw new IllegalArgumentException("INVALID_RECOVERY_OBSERVATION");
        }
    }
    public record Summary(int planned, int attempted, int recovered, int sessionLost, int recoveryFailed, int notRun,
                          Double sessionLossRate, Double recoverySuccessRate, Double observedLossLowerBound,
                          Double p50Millis, Double p95Millis, Double p99Millis, Double maxMillis) { }

    public static RecoveryBenchmarkReport build(String runId, RecoveryBenchmarkPlan plan, Map<String, Object> environment,
                                                 List<String> limitations, List<Case> cases, ObjectMapper mapper) {
        if (cases.size() != plan.samples().size()) throw new IllegalArgumentException("RECOVERY_DENOMINATOR_CHANGED");
        for (int i = 0; i < cases.size(); i++) if (!cases.get(i).sample().equals(plan.samples().get(i))) throw new IllegalArgumentException("RECOVERY_PLAN_CHANGED");
        Summary total = summarize(cases);
        Map<String, Summary> buckets = new LinkedHashMap<>();
        for (var fault : RecoveryBenchmarkPlan.Fault.values()) buckets.put(fault.name(), summarize(cases.stream()
                .filter(c -> c.sample().fault() == fault).toList()));
        boolean evaluable = "LIVE".equals(environment.get("mode")) && Boolean.TRUE.equals(environment.get("realStorageVerified"))
                && cases.size() >= 1000 && total.notRun() == 0 && total.recoveryFailed() == 0
                && cases.stream().allMatch(c -> c.attempted() && c.faultVerified())
                && buckets.values().stream().allMatch(s -> s.planned() > 0);
        // Integer arithmetic implements strict <0.5%; four losses /1000 pass, five do not.
        String target = !evaluable ? "NOT_EVALUABLE" : total.sessionLost() * 200L < total.attempted() ? "MET" : "NOT_MET";
        return new RecoveryBenchmarkReport("m19d-report-v1", runId, Instant.now().toString(), plan.seed(), hash(mapper, plan),
                Map.copyOf(environment), List.copyOf(limitations), total, buckets, target, List.copyOf(cases));
    }

    static Summary summarize(List<Case> cases) {
        int attempted = (int) cases.stream().filter(Case::attempted).count();
        int recovered = count(cases, Outcome.RECOVERED), lost = count(cases, Outcome.SESSION_LOST);
        int failed = count(cases, Outcome.RECOVERY_FAILED), notRun = count(cases, Outcome.NOT_RUN);
        boolean measured = attempted > 0 && notRun == 0 && failed == 0 && cases.stream().allMatch(Case::faultVerified);
        double[] times = cases.stream().filter(c -> c.attempted() && c.recoveryMillis() != null)
                .mapToDouble(Case::recoveryMillis).sorted().toArray();
        return new Summary(cases.size(), attempted, recovered, lost, failed, notRun,
                measured ? lost * 100.0 / attempted : null, attempted > 0 ? recovered * 100.0 / attempted : null,
                attempted > 0 ? lost * 100.0 / attempted : null, percentile(times, .5), percentile(times, .95),
                percentile(times, .99), times.length == 0 ? null : times[times.length - 1]);
    }

    private static int count(List<Case> cases, Outcome outcome) { return (int) cases.stream().filter(c -> c.evaluation().outcome() == outcome).count(); }
    private static Double percentile(double[] sorted, double p) { return sorted.length == 0 ? null : sorted[(int) Math.ceil(sorted.length * p) - 1]; }

    public Path write(Path directory, ObjectMapper mapper) throws IOException {
        Files.createDirectories(directory);
        String name = "recovery-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
                .format(Instant.now()) + "-" + runId.substring(0, 8);
        Path json = directory.resolve(name + ".json");
        Files.writeString(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        StringBuilder md = new StringBuilder("# M19D Memory & Recovery Benchmark\n\n");
        md.append("- Run: `").append(runId).append("`\n- Profile: `").append(environment.get("profile")).append("`\n")
                .append("- SessionLossRate: ").append(percent(overall.sessionLossRate())).append(" (strict target <0.5%)\n")
                .append("- RecoverySuccessRate: ").append(percent(overall.recoverySuccessRate())).append("\n")
                .append("- Target: **").append(targetStatus).append("**\n")
                .append("- Planned / attempted / recovered / lost / recovery failed / not run: ")
                .append(overall.planned()).append(" / ").append(overall.attempted()).append(" / ").append(overall.recovered()).append(" / ")
                .append(overall.sessionLost()).append(" / ").append(overall.recoveryFailed()).append(" / ").append(overall.notRun()).append("\n\n")
                .append("Recovery latency starts immediately before fault injection and ends after REST, history, memory and checkpoint checks. It includes restart, lease wait and bounded polling.\n\n")
                .append("| Fault | Attempts | Recovered | SESSION_LOST | Recovery failed | Not run | Loss rate | Success rate | P95 ms |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        byFault.forEach((fault, s) -> md.append("| ").append(fault).append(" | ").append(s.attempted()).append(" | ").append(s.recovered())
                .append(" | ").append(s.sessionLost()).append(" | ").append(s.recoveryFailed()).append(" | ").append(s.notRun())
                .append(" | ").append(percent(s.sessionLossRate())).append(" | ").append(percent(s.recoverySuccessRate()))
                .append(" | ").append(number(s.p95Millis())).append(" |\n"));
        md.append("\n## Limitations\n\n"); limitations.forEach(l -> md.append("- ").append(l).append('\n'));
        md.append("\n## Per sample observations\n\n| Case | Fault | Outcome | Recovery ms | Reasons |\n|---|---|---|---:|---|\n");
        cases.forEach(c -> md.append("| ").append(c.sample().caseId()).append(" | ").append(c.sample().fault()).append(" | ")
                .append(c.evaluation().outcome()).append(" | ").append(number(c.recoveryMillis())).append(" | ")
                .append(String.join(", ", c.evaluation().reasons())).append(" |\n"));
        Files.writeString(directory.resolve(name + ".md"), md, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return json;
    }
    private static String percent(Double value) { return value == null ? "N/A" : String.format(java.util.Locale.ROOT, "%.4f%%", value); }
    private static String number(Double value) { return value == null ? "N/A" : String.format(java.util.Locale.ROOT, "%.3f", value); }
}
