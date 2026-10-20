package com.wxx.aidocumentagent.ingestion.benchmark;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RabbitBenchmarkMetricsTest {
    @Test void retainsFirstStageAndCountsBatchOnceDespiteStageChangesAndRedelivery() {
        var metrics = new RabbitBenchmarkMetrics();
        var time = LocalDateTime.of(2026, 10, 11, 0, 0);
        metrics.started("b", "VECTOR_INDEX", 1, 50, time, time.plusSeconds(1));
        metrics.completed("b", "VECTOR_INDEX", RabbitBenchmarkMetrics.time(time.plusSeconds(2)));
        metrics.started("b", "KEYWORD_INDEX", 1, 50, time.plusSeconds(3), time.plusSeconds(4));
        metrics.completed("b", "KEYWORD_INDEX", RabbitBenchmarkMetrics.time(time.plusSeconds(5)));
        metrics.started("b", "VECTOR_INDEX", 1, 50, time.plusSeconds(6), time.plusSeconds(7));
        assertThat(metrics.stages()).hasSize(2);
        assertThat(metrics.stages().stream().filter(s -> s.stage.equals("VECTOR_INDEX")).findFirst().orElseThrow().enqueuedAt)
                .isEqualTo(RabbitBenchmarkMetrics.time(time));
        assertThat(metrics.counters().get("completedBatches")).isEqualTo(1L);
        assertThat(metrics.counters().get("completedChunks")).isEqualTo(50L);
    }
    @Test void readyCanRaceUploadResponseAndWarmupIsDiscardedOnlyAtPhaseBoundary() {
        var metrics = new RabbitBenchmarkMetrics();
        metrics.ready(1, 200);
        metrics.submitted(1, "doc-000", 0, 512, 100);
        metrics.acknowledged(1, 150);
        assertThat(metrics.isReady(1)).isTrue();
        assertThat(metrics.documents().getFirst().arrivedAt).isEqualTo(100.0);
        metrics.begin("measure");
        assertThat(metrics.documents()).isEmpty();
        assertThat(metrics.stages()).isEmpty();
    }
}
