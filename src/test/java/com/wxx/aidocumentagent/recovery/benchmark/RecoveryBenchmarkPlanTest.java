package com.wxx.aidocumentagent.recovery.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class RecoveryBenchmarkPlanTest {
    @Test void seededPlanIsReproducibleAndCoversEveryFaultAndLongHistory() {
        var plan = RecoveryBenchmarkPlan.generate(1000, 20261002);
        assertThat(plan).isEqualTo(RecoveryBenchmarkPlan.generate(1000, 20261002));
        assertThat(plan.samples()).hasSize(1000);
        assertThat(plan.samples().stream().map(RecoveryBenchmarkPlan.Sample::caseId).distinct()).hasSize(1000);
        for (var fault : RecoveryBenchmarkPlan.Fault.values()) assertThat(plan.samples().stream().filter(s -> s.fault() == fault).count()).isBetween(142L, 143L);
        assertThat(plan.samples().stream().filter(s -> s.historyTurns() * 2 > 20).count()).isEqualTo(100);
        assertThat(plan).isNotEqualTo(RecoveryBenchmarkPlan.generate(1000, 20261003));
    }
    @Test void fewerSamplesThanFaultTypesIsRejected() {
        assertThatThrownBy(() -> RecoveryBenchmarkPlan.generate(6, 1)).hasMessage("INVALID_SAMPLE_COUNT");
    }
    @Test void jsonObjectReorderingDoesNotChangeCheckpointDigestButMessageOrderDoes() {
        var mapper = new JsonMapper();
        assertThat(RecoveryBenchmarkObservation.hash(mapper, mapper.readTree("{\"answer\":\"a\",\"steps\":[{\"id\":\"x\",\"status\":\"SUCCEEDED\"}]}")))
                .isEqualTo(RecoveryBenchmarkObservation.hash(mapper, mapper.readTree("{\"steps\":[{\"status\":\"SUCCEEDED\",\"id\":\"x\"}],\"answer\":\"a\"}")));
        assertThat(RecoveryBenchmarkObservation.hash(mapper, java.util.List.of("a", "b")))
                .isNotEqualTo(RecoveryBenchmarkObservation.hash(mapper, java.util.List.of("b", "a")));
    }
}
