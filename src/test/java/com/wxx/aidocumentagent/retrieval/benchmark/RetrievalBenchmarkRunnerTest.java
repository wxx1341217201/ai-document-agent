package com.wxx.aidocumentagent.retrieval.benchmark;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.wxx.aidocumentagent.keyword.KeywordHit;
import com.wxx.aidocumentagent.keyword.KeywordIndex;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import com.wxx.aidocumentagent.retrieval.RetrievalProperties;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import com.wxx.aidocumentagent.retrieval.RetrievalChannel;
import com.wxx.aidocumentagent.retrieval.rerank.RankedChunk;
import com.wxx.aidocumentagent.retrieval.rerank.RerankingProperties;
import com.wxx.aidocumentagent.vector.VectorHit;
import com.wxx.aidocumentagent.vector.VectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalBenchmarkRunnerTest {
    @TempDir Path directory;
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void persistsFourComparisonsAndExactly500RawSamplesAfter50Warmups() throws Exception {
        var setup = setup(false);
        Path report = setup.runner().benchmark();
        var root = mapper.readTree(Files.readString(report));
        assertThat(root.get("status").asString()).isEqualTo("COMPLETE");
        assertThat(root.get("variants").size()).isEqualTo(4);
        assertThat(root.at("/latency/successes").asInt()).isEqualTo(500);
        assertThat(root.at("/latency/warmupErrors").asInt()).isZero();
        assertThat(root.at("/targets/status").asString()).isEqualTo("REGRESSION_ONLY");
        assertThat(root.at("/targets/claimEligible").asBoolean()).isFalse();
        assertThat(root.get("evidenceUnchanged").asBoolean()).isTrue();
        assertThat(setup.hybridCalls().get()).isEqualTo(400 + 50 + 500);
        List<String> journal = Files.readAllLines(Path.of(root.get("observations").asString()));
        assertThat(journal.stream().map(mapper::readTree).filter(item -> "SAMPLE".equals(item.get("phase").asString())))
                .hasSize(500);
        assertThat(journal.stream().map(mapper::readTree).filter(item -> "WARMUP".equals(item.get("phase").asString())))
                .hasSize(50);
        assertThat(Files.readString(report.resolveSibling(report.getFileName().toString().replace(".json", ".md"))))
                .contains("MRR@5", "p99", "ChatModel", "error_code");
    }

    @Test
    void warmupFailurePreventsLatencyClaimsAndKeepsFailureRecords() throws Exception {
        var setup = setup(true);
        var root = mapper.readTree(Files.readString(setup.runner().benchmark()));
        assertThat(root.get("status").asString()).isEqualTo("INVALID_LATENCY_RUN");
        assertThat(root.at("/latency/status").asString()).isEqualTo("INVALID_WARMUP");
        assertThat(root.at("/latency/warmupErrors").asInt()).isEqualTo(50);
        assertThat(root.at("/latency/samples").asInt()).isZero();
        assertThat(root.at("/targets/status").asString()).isEqualTo("NOT_EVALUATED");
        assertThat(setup.hybridCalls().get()).isEqualTo(450);
    }

    private Setup setup(boolean failWarmup) throws Exception {
        var cases = new ArrayList<RetrievalBenchmarkDataset.Case>();
        var tags = List.of("exact", "semantic", "chinese_synonym", "distractor", "error_code", "identifier");
        for (int index = 0; index < 199; index++) {
            cases.add(new RetrievalBenchmarkDataset.Case("Q" + index, 2, "query", true, List.of(1L),
                    index == 0 ? List.of("cross_context", "cross_document") : List.of(tags.get(index % tags.size()))));
        }
        cases.set(0, new RetrievalBenchmarkDataset.Case("Q0", 2, "query", true, List.of(1L, 2L), List.of("cross_context", "cross_document")));
        cases.add(new RetrievalBenchmarkDataset.Case("N", 2, "unknown", false, List.of(), List.of("unanswerable")));
        Path labels = directory.resolve("dataset.jsonl");
        StringBuilder lines = new StringBuilder();
        cases.forEach(item -> lines.append(mapper.writeValueAsString(item)).append('\n'));
        Files.writeString(labels, lines);
        var source = new RetrievalBenchmarkCorpus(List.of(
                new RetrievalBenchmarkCorpus.Chunk(1, 2, 7, 0, "one", "title", "section", null, null, "test"),
                new RetrievalBenchmarkCorpus.Chunk(2, 2, 8, 0, "two", "title", "section", null, null, "test")));
        var bootstrap = mock(RetrievalBenchmarkCorpusBootstrapper.class);
        when(bootstrap.readCorpus(2)).thenReturn(source);
        var vector = mock(VectorIndex.class);
        when(vector.search(any())).thenReturn(List.of(new VectorHit("point", 1, 7, 2, 0,
                null, null, source.chunks().getFirst().contentHash(), "test", "one", 1)));
        var keyword = mock(KeywordIndex.class);
        when(keyword.search(any())).thenReturn(List.of(new KeywordHit(1, 2, 7, "one", "title", "section", null, null,
                source.chunks().getFirst().contentHash(), 1)));
        var chunk = new RetrievedChunk(1, 2, 7, "one", null, null, 1, 1D, 1, 1D, 0.03, List.of(RetrievalChannel.VECTOR, RetrievalChannel.KEYWORD));
        var hybrid = mock(HybridRetriever.class);
        AtomicInteger calls = new AtomicInteger();
        when(hybrid.retrieve(any(), anyBoolean())).thenAnswer(invocation -> {
            boolean rerank = invocation.getArgument(1);
            int ordinal = calls.incrementAndGet();
            boolean degraded = failWarmup && ordinal > 400;
            return new RetrievalResult(List.of(chunk), false, List.of(),
                    List.of(rerank && !degraded ? new RankedChunk(chunk, 1, 1, 1D) : RankedChunk.rrf(1, chunk)),
                    rerank && !degraded, degraded, Duration.ofNanos(1));
        });
        var properties = new RerankingProperties();
        properties.setEnabled(true);
        properties.setUrl(URI.create("http://127.0.0.1/rerank"));
        var environment = new MockEnvironment().withProperty("app.benchmark.retrieval.dataset", labels.toString())
                .withProperty("app.benchmark.retrieval.report-directory", directory.resolve("reports").toString())
                .withProperty("app.benchmark.retrieval.profile", "FAKE_FIXED_LATENCY")
                .withProperty("app.benchmark.retrieval.provider", "test fake").withProperty("app.benchmark.retrieval.region", "localhost");
        return new Setup(new RetrievalBenchmarkRunner(vector, keyword, hybrid, new RetrievalProperties(), properties,
                mapper, environment, bootstrap), calls);
    }

    private record Setup(RetrievalBenchmarkRunner runner, AtomicInteger hybridCalls) { }
}
