package com.wxx.aidocumentagent.rag.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.chunking.TextChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.api.dto.RagQueryResponse;
import com.wxx.aidocumentagent.rag.api.dto.RagRetrievalResponse;
import com.wxx.aidocumentagent.rag.application.RagChatCompletion;
import com.wxx.aidocumentagent.rag.application.RagEvidenceChunk;
import com.wxx.aidocumentagent.rag.application.RagQueryApplicationService;
import com.wxx.aidocumentagent.rag.application.RagQueryCommand;
import com.wxx.aidocumentagent.rag.application.RagQueryExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagAccuracyBenchmarkRunnerTest {

    @TempDir
    Path directory;
    private final JsonMapper mapper = new JsonMapper();
    private final Path datasetPath = Path.of("benchmarks/rag/qa-dataset.jsonl");
    private RagQueryApplicationService rag;
    private DocumentChunkRepository chunks;
    private RagAccuracyDataset dataset;
    private Map<Long, DocumentChunk> corpus;

    @BeforeEach
    void setup() throws Exception {
        rag = mock(RagQueryApplicationService.class);
        chunks = mock(DocumentChunkRepository.class);
        dataset = RagAccuracyDataset.load(datasetPath, mapper);
        Map<Long, List<String>> quotes = new HashMap<>();
        dataset.samples().forEach(sample -> sample.requiredFacts().forEach(fact -> fact.supportingChunkIds()
                .forEach(id -> quotes.computeIfAbsent(id, ignored -> new ArrayList<>()).add(fact.sourceQuote()))));
        corpus = new HashMap<>();
        quotes.forEach((id, values) -> {
            String text = String.join("\n", values.stream().distinct().toList());
            DocumentChunk chunk = DocumentChunk.create(2, id < 250 ? 10 : 20,
                    new TextChunk(0, text, 20, null, null, null, Map.of()));
            ReflectionTestUtils.setField(chunk, "id", id);
            corpus.put(id, chunk);
        });
        when(chunks.findByKnowledgeBaseIdAndIdIn(eq(2L), anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(1);
            return ids.stream().map(corpus::get).filter(java.util.Objects::nonNull).toList();
        });
        when(chunks.findByKnowledgeBaseIdOrderByIdAsc(2L)).thenAnswer(invocation -> new ArrayList<>(corpus.values()));
        when(rag.queryForConversation(eq(2L), any(), anyList())).thenAnswer(invocation -> {
            RagQueryCommand command = invocation.getArgument(1);
            var sample = dataset.samples().stream().filter(item -> item.question().equals(command.question()))
                    .findFirst().orElseThrow();
            if (!sample.answerable()) {
                return new RagQueryExecution(new RagQueryResponse("我不知道，请补充资料。", List.of(),
                        new RagRetrievalResponse(false, 0)), null, List.of(), "b".repeat(64));
            }
            DocumentChunk chunk = corpus.get(sample.acceptableChunkIds().getFirst());
            var citation = new RagCitationResponse("C1", chunk.getDocumentId(), "unit-test-fixture.txt", chunk.getId(),
                    null, null, chunk.getContent());
            var evidence = new RagEvidenceChunk(2, citation, chunk.getContent(), BenchmarkText.sha256(chunk.getContent()));
            String answer = "<think>never persist hidden test reasoning</think>" + sample.referenceAnswer() + " [C1]";
            return new RagQueryExecution(new RagQueryResponse(answer, List.of(citation), new RagRetrievalResponse(false, 1)),
                    new RagChatCompletion(answer, "fake-test-model", 1, 1, 2), List.of(evidence), "b".repeat(64));
        });
    }

    @Test
    void runs200QaWritesResultsAndKeepsDraftHumanVerdictsPending() throws Exception {
        Path reportPath = runner().benchmark(datasetPath, directory);
        String json = Files.readString(reportPath);
        var report = mapper.readValue(json, RagAccuracyReport.class);
        assertThat(report.overall().answerable()).isEqualTo(190);
        assertThat(report.overall().unanswerable()).isEqualTo(10);
        assertThat(report.overall().pendingReview()).isEqualTo(200);
        assertThat(report.overall().qaAccuracy()).isNull();
        assertThat(report.overall().refusalAccuracy()).isNull();
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        assertThat(report.cases()).hasSize(200);
        assertThat(json).doesNotContain("never persist hidden", "<think>");
        assertThat(report.byType().get(RagAccuracyDataset.QuestionType.CROSS_DOCUMENT).answerable()).isEqualTo(30);
        verify(rag, times(200)).queryForConversation(eq(2L), any(), anyList());
        for (var sample : dataset.samples()) {
            if (sample.type() == RagAccuracyDataset.QuestionType.MULTI_TURN) {
                verify(rag).queryForConversation(eq(2L), eq(new RagQueryCommand(sample.question(), 8, true)), eq(sample.history()));
            }
        }
        Path template = Path.of(reportPath.toString().replace(".json", "-review-template.jsonl"));
        assertThat(Files.readAllLines(template)).hasSize(200);
        var unchanged = RagAccuracyBenchmarkRunner.applyReviews(reportPath, template, mapper);
        assertThat(unchanged.overall().qaAccuracy()).isNull();
        assertThat(unchanged.overall().pendingReview()).isEqualTo(200);
    }

    @Test
    void stopsAfterThreeServiceErrorsKeepsEntireDenominatorAndOmitsExceptionSecrets() throws Exception {
        when(rag.queryForConversation(eq(2L), any(), anyList()))
                .thenThrow(new IllegalStateException("provider secret=must-never-appear"));
        Path path = runner().benchmark(datasetPath, directory);
        String json = Files.readString(path);
        var report = mapper.readValue(json, RagAccuracyReport.class);
        assertThat(report.overall().errors()).isEqualTo(3);
        assertThat(report.overall().notRun()).isEqualTo(197);
        assertThat(report.overall().answerable()).isEqualTo(190);
        assertThat(json).doesNotContain("must-never-appear");
        verify(rag, times(3)).queryForConversation(eq(2L), any(), anyList());
    }

    @Test
    void foreignCorpusLabelsAbortBeforeAnyModelRequest() throws Exception {
        ReflectionTestUtils.setField(corpus.values().iterator().next(), "knowledgeBaseId", 99L);
        Path path = runner().benchmark(datasetPath, directory);
        var report = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        assertThat(report.overall().notRun()).isEqualTo(200);
        assertThat(report.limitations()).anyMatch(item -> item.contains("FOREIGN_OR_INVALID"));
        verifyNoInteractions(rag);
    }

    @Test
    void editedOrTruncatedReportCannotReduceDenominatorDuringReview() throws Exception {
        Path path = runner().benchmark(datasetPath, directory);
        var original = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        var truncated = RagAccuracyReport.build(original.runId(), original.createdAt(), original.dataset(),
                original.configuration(), original.limitations(), original.cases().subList(0, 199));
        Path altered = directory.resolve("altered.json");
        Files.writeString(altered, mapper.writeValueAsString(truncated));
        Path reviews = directory.resolve("empty.jsonl");
        Files.writeString(reviews, "");
        assertThatThrownBy(() -> RagAccuracyBenchmarkRunner.applyReviews(altered, reviews, mapper))
                .hasMessageContaining("denominator");
    }

    @Test
    void generatedReportAndReviewRoundTripRecognizeOneHumanVerifiedCase() throws Exception {
        Path path = runner().benchmark(datasetPath, directory);
        var original = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        var first = original.cases().getFirst();
        var review = new RagAccuracyEvaluator.Review(original.runId(), original.dataset().sha256(), first.sample().id(),
                first.resultSha256(), "unit-test-reviewer", "2026-10-02T12:00:00Z", true,
                first.sample().requiredFacts().stream().map(fact -> new RagAccuracyEvaluator.FactReview(fact.id(), true, List.of("C1"))).toList(),
                true, true, null, "synthetic unit test only");
        Path reviews = directory.resolve("one-review.jsonl");
        Files.writeString(reviews, mapper.writeValueAsString(review));
        var reviewed = RagAccuracyBenchmarkRunner.applyReviews(path, reviews, mapper);
        assertThat(reviewed.overall().correct()).isEqualTo(1);
        assertThat(reviewed.overall().pendingReview()).isEqualTo(199);
        assertThat(reviewed.overall().qaAccuracy()).isNull();
        assertThat(reviewed.overall().qaAccuracyLowerBound()).isCloseTo(1.0 / 190, org.assertj.core.data.Offset.offset(1e-9));
    }

    private RagAccuracyBenchmarkRunner runner() {
        return new RagAccuracyBenchmarkRunner(rag, chunks, new RagProperties(), new MockEnvironment(), mapper);
    }

    @Test
    void immutableDatasetSnapshotAllowsReviewAfterEditingTheWorkingDataset() throws Exception {
        Path working = directory.resolve("working-dataset.jsonl");
        Files.copy(datasetPath, working);
        Path path = runner().benchmark(working, directory);
        var original = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        assertThat(Path.of(String.valueOf(original.configuration().get("datasetSnapshotPath"))))
                .hasContent(Files.readString(datasetPath));
        Files.writeString(working, "edited working file");
        Path reviews = directory.resolve("empty-reviews.jsonl");
        Files.writeString(reviews, "");
        var reviewed = RagAccuracyBenchmarkRunner.applyReviews(path, reviews, mapper);
        assertThat(reviewed.overall().pendingReview()).isEqualTo(200);
    }

    @Test
    void preflightFreezesEntireCorpusWithoutCallingRag() throws Exception {
        var environment = new MockEnvironment().withProperty("app.benchmark.rag.preflight-only", "true");
        Path path = new RagAccuracyBenchmarkRunner(rag, chunks, new RagProperties(), environment, mapper)
                .benchmark(datasetPath, directory);
        var report = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        assertThat(report.overall().notRun()).isEqualTo(200);
        assertThat(report.configuration().get("corpusStable")).isEqualTo(true);
        assertThat(report.configuration().get("corpusChunks")).isEqualTo(corpus.size());
        Path snapshot = Path.of(String.valueOf(report.configuration().get("corpusSnapshotPath")));
        assertThat(RagAccuracyCorpus.load(snapshot, 2L, mapper).chunks()).hasSize(corpus.size());
        assertThat(report.cases()).allSatisfy(item -> assertThat(item.observation().errorCode()).isEqualTo("CORPUS_PREFLIGHT_ONLY"));
        verifyNoInteractions(rag);
    }

    @Test
    void modifiedCorpusSnapshotAndStaleBlankTemplateAreRejectedOffline() throws Exception {
        Path path = runner().benchmark(datasetPath, directory);
        var original = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        Path template = Path.of(path.toString().replace(".json", "-review-template.jsonl"));
        List<String> rows = new ArrayList<>(Files.readAllLines(template));
        rows.set(0, rows.getFirst().replace(original.runId(), "another-run"));
        Path stale = directory.resolve("stale-template.jsonl");
        Files.write(stale, rows);
        assertThatThrownBy(() -> RagAccuracyBenchmarkRunner.applyReviews(path, stale, mapper))
                .hasMessageContaining("Unbound human review template");
        Path snapshot = Path.of(String.valueOf(original.configuration().get("corpusSnapshotPath")));
        Files.writeString(snapshot, "\n", java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> RagAccuracyBenchmarkRunner.applyReviews(path, template, mapper))
                .hasMessageContaining("Corpus snapshot changed");
    }

    @Test
    void evidenceVerificationFailurePreservesAnswerAndCitationsForDiagnosis() throws Exception {
        var source = corpus.values().iterator().next();
        var citation = new RagCitationResponse("C1", source.getDocumentId(), "unit-test-fixture.txt", source.getId(),
                null, null, source.getContent());
        var forged = new RagEvidenceChunk(2L, citation, "invented context", "a".repeat(64));
        when(rag.queryForConversation(eq(2L), any(), anyList())).thenReturn(new RagQueryExecution(
                new RagQueryResponse("Actual visible model answer [C1]", List.of(citation), new RagRetrievalResponse(false, 1)),
                new RagChatCompletion("Actual visible model answer [C1]", "fake-test-model", 1, 1, 2),
                List.of(forged), "b".repeat(64)));
        Path path = runner().benchmark(datasetPath, directory);
        var report = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        assertThat(report.overall().errors()).isEqualTo(3);
        assertThat(report.failureDetails().getFirst().answer()).isEqualTo("Actual visible model answer [C1]");
        assertThat(report.failureDetails().getFirst().citations()).containsExactly(citation);
        assertThat(report.failureDetails().getFirst().failureReasons()).contains("UNVERIFIED_CURRENT_CONTEXT");
        assertThat(Files.readAllLines(Path.of(path.toString().replace(".json", "-failures.jsonl")))).hasSize(200);
    }

    @Test
    void changingAnUnlabeledChunkAlsoInvalidatesTheCorpus() throws Exception {
        var unlabeled = DocumentChunk.create(2L, 30L,
                new TextChunk(0, "source not used by answerable labels", 20, null, null, null, Map.of()));
        ReflectionTestUtils.setField(unlabeled, "id", 777L);
        corpus.put(777L, unlabeled);
        when(rag.queryForConversation(eq(2L), any(), anyList())).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(unlabeled, "content", "modified unanswerable evidence universe");
            return new RagQueryExecution(new RagQueryResponse("我不知道。", List.of(), new RagRetrievalResponse(false, 0)), null);
        });
        Path path = runner().benchmark(datasetPath, directory);
        var report = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        assertThat(report.configuration().get("corpusStable")).isEqualTo(false);
        assertThat(report.limitations()).contains("CORPUS_CHANGED_DURING_RUN");
    }

    @Test
    void persistentRetrievalDegradationStopsRequestsWithoutDroppingSamples() throws Exception {
        when(rag.queryForConversation(eq(2L), any(), anyList())).thenReturn(new RagQueryExecution(
                new RagQueryResponse("我不知道。", List.of(), new RagRetrievalResponse(true, 0)), null));
        Path path = runner().benchmark(datasetPath, directory);
        var report = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        assertThat(report.overall().degraded()).isEqualTo(3);
        assertThat(report.overall().notRun()).isEqualTo(197);
        assertThat(report.overall().answerable()).isEqualTo(190);
        assertThat(report.cases().get(3).observation().errorCode()).isEqualTo("STOPPED_AFTER_CONSECUTIVE_DEGRADED_REQUESTS");
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
        verify(rag, times(3)).queryForConversation(eq(2L), any(), anyList());
    }

    @Test
    void pairedSnapshotsCanBeReviewedAfterMovingToAnotherDirectory() throws Exception {
        Path path = runner().benchmark(datasetPath, directory);
        var original = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        Path moved = Files.createDirectory(directory.resolve("moved"));
        Path datasetSnapshot = Path.of(String.valueOf(original.configuration().get("datasetSnapshotPath")));
        Path corpusSnapshot = Path.of(String.valueOf(original.configuration().get("corpusSnapshotPath")));
        Files.move(datasetSnapshot, moved.resolve(datasetSnapshot.getFileName()));
        Files.move(corpusSnapshot, moved.resolve(corpusSnapshot.getFileName()));
        Path movedReport = moved.resolve(path.getFileName());
        Files.move(path, movedReport);
        Path template = Path.of(path.toString().replace(".json", "-review-template.jsonl"));
        assertThat(RagAccuracyBenchmarkRunner.applyReviews(movedReport, template, mapper).overall().pendingReview()).isEqualTo(200);
    }

    @Test
    void corpusChangesDuringGenerationInvalidateTheRun() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(rag.queryForConversation(eq(2L), any(), anyList())).thenAnswer(invocation -> {
            if (first.getAndSet(false)) {
                DocumentChunk source = corpus.values().iterator().next();
                ReflectionTestUtils.setField(source, "content", "changed prefix\n" + source.getContent());
            }
            return new RagQueryExecution(new RagQueryResponse("我不知道，请补充资料。", List.of(),
                    new RagRetrievalResponse(false, 0)), null);
        });
        Path path = runner().benchmark(datasetPath, directory);
        var report = mapper.readValue(Files.readString(path), RagAccuracyReport.class);
        assertThat(report.configuration().get("corpusStable")).isEqualTo(false);
        assertThat(report.limitations()).contains("CORPUS_CHANGED_DURING_RUN");
        assertThat(report.overall().errors()).isZero();
        assertThat(report.targetStatus()).isEqualTo("NOT_EVALUABLE");
    }
}
