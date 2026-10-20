package com.wxx.aidocumentagent.agent.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentRouter;
import com.wxx.aidocumentagent.agent.AgentUsage;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.mockito.stubbing.Answer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentBenchmarkRunnerTest {
    @TempDir Path temp;
    private final JsonMapper mapper = new JsonMapper();
    private final Path source = Path.of("benchmarks/agent/agent-tasks.jsonl");
    private AgentRouter router;
    private AgentBenchmarkCorpus corpus;
    private AgentBenchmarkSessions sessions;
    private AgentBenchmarkDataset dataset;
    private AgentBenchmarkCorpus.Baseline baseline;
    private Answer<AgentResult> routerAnswer;
    private final List<List<AgentMemoryMessage>> seenHistory = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        router = mock(AgentRouter.class); corpus = mock(AgentBenchmarkCorpus.class); sessions = mock(AgentBenchmarkSessions.class);
        dataset = AgentBenchmarkDataset.load(source, mapper);
        Map<Long, AgentBenchmarkCorpus.Source> sources = new java.util.HashMap<>();
        for (var task : dataset.tasks()) for (long chunk : task.acceptableChunkIds()) {
            String text = "Unit fixture source " + chunk + " " + dataset.tasks().stream().flatMap(t -> t.requiredFacts().stream())
                    .filter(f -> f.supportingChunkIds().contains(chunk)).map(f -> f.sourceQuote())
                    .distinct().collect(java.util.stream.Collectors.joining(" "));
            sources.put(chunk, new AgentBenchmarkCorpus.Source(chunk < 250 ? 10 : 20, "unit-fixture.txt", text,
                    AgentBenchmarkText.sha256(text), null, null));
        }
        baseline = new AgentBenchmarkCorpus.Baseline(sources, "f".repeat(64));
        when(corpus.snapshot(any())).thenReturn(baseline); when(corpus.stable(2, baseline)).thenReturn(true);
        when(corpus.verify(eq(2L), eq(baseline), anyList())).thenAnswer(call -> {
            List<AgentBenchmarkTrace.Evidence> snapshots = call.getArgument(2);
            return snapshots.stream().map(e -> new RagEvidenceChunk(2, AgentBenchmarkEvaluator.toRag(e.citation()), e.contextText(), baseline.sources().get(e.citation().chunkId()).contentSha256())).toList();
        });
        AtomicLong ids = new AtomicLong(1000);
        when(sessions.open(eq(2L), any(), anyString(), anyString())).thenAnswer(call -> ids.incrementAndGet());
        routerAnswer = call -> {
            AgentRequest request = call.getArgument(0); AgentExecutionContext context = call.getArgument(1);
            seenHistory.add(context.memory().messages());
            var task = dataset.tasks().stream().filter(t -> t.mode() == request.mode() && t.turns().contains(request.question())).findFirst().orElseThrow();
            List<AgentCitation> citations = new ArrayList<>();
            for (long chunk : task.acceptableChunkIds()) {
                String text = "Unit fixture source " + chunk;
                var c = new AgentCitation(2, "C" + (citations.size() + 1), chunk < 250 ? 10 : 20, "unit-fixture.txt", chunk, null, null, text);
                citations.add(c); context.observeEvidence(c, text);
            }
            String answer = task.answerable() ? "ACTUAL_UNIT_TURN " + task.referenceAnswer() + " "
                    + String.join(" ", citations.stream().map(c -> "[" + c.citationId() + "]").toList()) : "资料不足，请补充资料。";
            // Router is intentionally mocked: these artifacts exist only inside @TempDir.
            return new AgentResult(answer, citations, List.of(), List.of(), AgentResult.Status.COMPLETED,
                    new AgentUsage(null, null, null, 1, 0), null, "unit-test-fake-model");
        };
        when(router.execute(any(), any())).thenAnswer(routerAnswer);
    }
    private AgentBenchmarkRunner runner() { return new AgentBenchmarkRunner(router, sessions, corpus,
            new MockEnvironment().withProperty("app.benchmark.agent.deployment", "unit-tests-only"),
            new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties(), mapper); }
    private AgentBenchmarkRunner runner(MockEnvironment environment, LongSupplier nanoTime) {
        return new AgentBenchmarkRunner(router, sessions, corpus, environment,
                new RetrievalFirstProperties(), new ReActProperties(), new PlanProperties(), mapper, nanoTime);
    }

    @Test void realSequentialHistoryFullDenominatorAndThreeModeReports() throws Exception {
        Path path = runner().runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        assertThat(report.cases()).hasSize(100);
        assertThat(report.overall().executed()).isEqualTo(100);
        assertThat(report.overall().taskSuccessRate()).isNull();
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(report.byMode()).hasSize(3);
        verify(router, times(dataset.tasks().stream().mapToInt(t -> t.turns().size()).sum())).execute(any(), any());
        assertThat(seenHistory.stream().filter(h -> !h.isEmpty())).allMatch(h -> h.getLast().content().startsWith("ACTUAL_UNIT_TURN"));
        verify(sessions, times(19)).close(eq(2L), anyLong());
        var template = Path.of(path.toString().replace(".json", "-review-template.jsonl"));
        assertThat(Files.readAllLines(template)).hasSize(100);
        assertThat(AgentBenchmarkRunner.applyReviews(path, template, mapper).overall().taskSuccessRate()).isNull();
    }
    @Test void limitsOutagesPerModeRetains100CasesAndDropsRawExceptionText() throws Exception {
        doThrow(new IllegalStateException("SECRET_UNIT_EXCEPTION_BODY")).when(router).execute(any(), any());
        Path path = runner().runBenchmark(source, temp);
        String json = Files.readString(path);
        var report = mapper.readValue(json, AgentBenchmarkReport.class);
        assertThat(report.overall().errors()).isEqualTo(9); assertThat(report.overall().notRun()).isEqualTo(91);
        assertThat(report.cases()).hasSize(100); assertThat(report.byMode().values()).allMatch(s -> s.errors() == 3);
        verify(router, times(9)).execute(any(), any());
        assertThat(json).doesNotContain("SECRET_UNIT_EXCEPTION_BODY");
    }
    @Test void preflightMismatchCallsNoAgentAndKeepsAllModes() throws Exception {
        when(corpus.snapshot(any())).thenThrow(new IllegalArgumentException("MISSING_OR_FOREIGN_LABELED_CHUNK"));
        Path path = runner().runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        assertThat(report.overall().notRun()).isEqualTo(100); assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        verifyNoInteractions(router, sessions);
    }
    @Test void changedCorpusDisablesTargetAndCleanupFailuresRemainErrors() throws Exception {
        when(corpus.stable(2, baseline)).thenReturn(false);
        doThrow(new IllegalStateException("PRIVATE_CLEANUP_DETAIL")).when(sessions).close(eq(2L), anyLong());
        Path path = runner().runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        assertThat(report.configuration().get("corpusStable")).isEqualTo(false);
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(report.cases().stream().anyMatch(c -> c.observation().errorCode() != null
                && c.observation().errorCode().startsWith("SESSION_CLEANUP_FAILED"))).isTrue();
        assertThat(Files.readString(path)).doesNotContain("PRIVATE_CLEANUP_DETAIL");
    }
    @Test void reviewUsesFrozenDatasetAndRejectsConfigurationOrObservationEdits() throws Exception {
        Path working = temp.resolve("tasks.jsonl"); Files.copy(source, working);
        Path path = runner().runBenchmark(working, temp);
        var template = Path.of(path.toString().replace(".json", "-review-template.jsonl"));
        Files.writeString(working, "changed working dataset");
        assertThat(AgentBenchmarkRunner.applyReviews(path, template, mapper).cases()).hasSize(100);
        var node = (ObjectNode) mapper.readTree(Files.readString(path));
        ((ObjectNode) node.get("configuration")).put("mode", "FAKE");
        Path edited = temp.resolve("edited.json"); Files.writeString(edited, mapper.writeValueAsString(node));
        assertThatThrownBy(() -> AgentBenchmarkRunner.applyReviews(edited, template, mapper)).hasMessageContaining("modified run inputs");
    }
    @Test void boundHumanReviewUpdatesOneCaseWithoutModelCalls() throws Exception {
        Path path = runner().runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        var item = report.cases().getFirst();
        var review = new AgentBenchmarkEvaluator.Review(report.runId(), report.dataset().sha256(), item.task().caseId(), item.resultSha256(),
                "unit-test-reviewer", "2026-10-02T00:00:00Z", true, item.task().requiredFacts().stream()
                    .map(f -> new com.wxx.aidocumentagent.rag.benchmark.RagAccuracyEvaluator.FactReview(f.id(), true, List.of("C1"))).toList(),
                true, true, null, true, true, "Fixture only.");
        Path reviews = temp.resolve("reviews.jsonl"); Files.writeString(reviews, mapper.writeValueAsString(review));
        clearInvocations(router);
        var revised = AgentBenchmarkRunner.applyReviews(path, reviews, mapper);
        assertThat(revised.overall().succeeded()).isEqualTo(1);
        assertThat(revised.overall().taskSuccessRate()).isNull();
        verifyNoInteractions(router);
    }

    @Test void smokeLimitExercisesAllThreeModesAndPreservesFullDenominator() throws Exception {
        var environment = new MockEnvironment().withProperty("app.benchmark.agent.max-tasks-per-mode", "1");
        Path path = runner(environment, System::nanoTime).runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        assertThat(report.cases()).hasSize(100);
        assertThat(report.overall().executed()).isEqualTo(3);
        assertThat(report.overall().notRun()).isEqualTo(97);
        assertThat(report.byMode().values()).allMatch(s -> s.executed() == 1);
        assertThat(report.byMode().get(com.wxx.aidocumentagent.agent.AgentMode.RETRIEVAL_FIRST).tasks()).isEqualTo(30);
        assertThat(report.byMode().get(com.wxx.aidocumentagent.agent.AgentMode.REACT).tasks()).isEqualTo(35);
        assertThat(report.byMode().get(com.wxx.aidocumentagent.agent.AgentMode.PLAN_EXECUTE_REFLECT).tasks()).isEqualTo(35);
        assertThat(report.overall().taskSuccessRate()).isNull();
        assertThat(report.status()).isEqualTo("INCOMPLETE_RUN");
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(report.cases().stream().filter(c -> !c.observation().executed()))
                .allMatch(c -> "MODE_SAMPLE_LIMIT".equals(c.observation().errorCode()));
        assertThat(Files.readAllLines(Path.of(path.toString().replace(".json", "-review-template.jsonl")))).hasSize(3);
        verify(router, times(3)).execute(any(), any());
    }

    @Test void exhaustedTotalBudgetStopsNewTasksAndBoundsTheRequestDeadline() throws Exception {
        var nanoTime = new AtomicLong();
        var environment = new MockEnvironment().withProperty("app.benchmark.agent.max-run-seconds", "10");
        doAnswer(call -> {
            AgentExecutionContext context = call.getArgument(1);
            assertThat(context.remainingTime()).isLessThanOrEqualTo(java.time.Duration.ofSeconds(10));
            var answer = routerAnswer.answer(call);
            nanoTime.set(java.time.Duration.ofSeconds(11).toNanos());
            return answer;
        }).when(router).execute(any(), any());
        Path path = runner(environment, nanoTime::get).runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        assertThat(report.overall().executed()).isEqualTo(1);
        assertThat(report.overall().notRun()).isEqualTo(99);
        assertThat(report.cases().stream().skip(1)).allMatch(c -> "RUN_TIME_BUDGET_EXCEEDED".equals(c.observation().errorCode()));
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        verify(router).execute(any(), any());
    }

    @Test void exhaustedBudgetStopsLaterTurnsAndStillCleansUpTheOwnedSession() throws Exception {
        var nanoTime = new AtomicLong();
        var environment = new MockEnvironment().withProperty("app.benchmark.agent.max-run-seconds", "10");
        var firstMulti = dataset.tasks().stream().filter(t -> t.turns().size() > 1).findFirst().orElseThrow();
        doAnswer(call -> {
            AgentRequest request = call.getArgument(0);
            var answer = routerAnswer.answer(call);
            if (request.conversationId() != null) nanoTime.set(java.time.Duration.ofSeconds(11).toNanos());
            return answer;
        }).when(router).execute(any(), any());
        Path path = runner(environment, nanoTime::get).runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        var interrupted = report.cases().stream().filter(c -> c.task().caseId().equals(firstMulti.caseId())).findFirst().orElseThrow();
        assertThat(interrupted.observation().turns()).hasSize(1);
        assertThat(interrupted.evaluation().status()).isEqualTo(AgentBenchmarkEvaluator.Status.ERROR);
        assertThat(interrupted.evaluation().failureReasons()).contains("RUN_TIME_BUDGET_EXCEEDED");
        verify(router, times(dataset.tasks().indexOf(firstMulti) + 1)).execute(any(), any());
        verify(sessions).close(eq(2L), anyLong());
    }

    @Test void invalidRunBudgetsAreRejectedBeforeAnyExternalCalls() {
        for (String seconds : List.of("0", "3601")) {
            var environment = new MockEnvironment().withProperty("app.benchmark.agent.max-run-seconds", seconds);
            assertThatThrownBy(() -> runner(environment, System::nanoTime).runBenchmark(source, temp))
                    .hasMessageContaining("max-run-seconds must be 1..3600");
        }
        for (String limit : List.of("-1", "100001")) {
            var environment = new MockEnvironment().withProperty("app.benchmark.agent.max-tasks-per-mode", limit);
            assertThatThrownBy(() -> runner(environment, System::nanoTime).runBenchmark(source, temp))
                    .hasMessageContaining("max-tasks-per-mode must be 0..100000");
        }
        verifyNoInteractions(router, corpus, sessions);
    }

    @Test void preflightFreezesCorpusWithoutCreatingSessionsOrCallingAgent() throws Exception {
        var env = new MockEnvironment().withProperty("app.benchmark.agent.preflight-only", "true");
        Path path = runner(env, System::nanoTime).runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        assertThat(report.overall().notRun()).isEqualTo(100);
        assertThat(report.cases()).allMatch(c -> "CORPUS_PREFLIGHT_ONLY".equals(c.observation().errorCode()));
        assertThat(AgentBenchmarkAudit.verify(path, mapper).get("status")).isEqualTo("PASSED");
        verifyNoInteractions(router, sessions);
    }

    @Test void auditRecomputesVerdictsAndRejectsEditedSummaryOrCorpus() throws Exception {
        Path path = runner().runBenchmark(source, temp);
        assertThat(AgentBenchmarkAudit.verify(path, mapper).get("status")).isEqualTo("PASSED");
        var json = (ObjectNode) mapper.readTree(Files.readString(path));
        ((ObjectNode) json.get("overall")).put("succeeded", 100);
        Path edited = temp.resolve("edited-metrics.json"); Files.writeString(edited, mapper.writeValueAsString(json));
        assertThatThrownBy(() -> AgentBenchmarkAudit.verify(edited, mapper)).hasMessageContaining("metrics differ");
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        Files.writeString(Path.of((String) report.configuration().get("corpusSnapshotPath")), "modified corpus");
        assertThatThrownBy(() -> AgentBenchmarkAudit.verify(path, mapper)).hasMessage("Corpus snapshot changed");
    }

    @Test void liveStartupForcesReadOnlyIndicesAndDisablesBackgroundMutations() {
        var arguments = AgentBenchmarkRunner.liveArguments(new String[]{"--live", "--spring.flyway.enabled=true",
                "--app.keyword.initialize-schema=true", "--app.vector.qdrant.initialization-strategy=CREATE_IF_MISSING",
                "--app.agent.execution.enabled", "--app.ingestion.enabled=true"});
        assertThat(arguments).contains("--spring.flyway.enabled=false", "--app.keyword.initialize-schema=false",
                "--app.vector.qdrant.initialization-strategy=VALIDATE_EXISTING", "--app.ingestion.enabled=false",
                "--app.agent.execution.enabled=false");
        assertThat(arguments).doesNotContain("--live", "--app.agent.execution.enabled", "--spring.flyway.enabled=true",
                "--app.keyword.initialize-schema=true", "--app.vector.qdrant.initialization-strategy=CREATE_IF_MISSING");
    }

    @Test void optionalAcceptanceGateCannotPassUnreviewedRun() throws Exception {
        var env = new MockEnvironment().withProperty("app.benchmark.agent.max-tasks-per-mode", "1");
        Path path = runner(env, System::nanoTime).runBenchmark(source, temp);
        var report = mapper.readValue(Files.readString(path), AgentBenchmarkReport.class);
        AgentBenchmarkRunner.requireTarget(report, false);
        assertThatThrownBy(() -> AgentBenchmarkRunner.requireTarget(report, true))
                .hasMessage("M19C_TARGET_UNMET_OR_UNVERIFIED:NOT_EVALUABLE");
        assertThat(path).isRegularFile();
    }
}
