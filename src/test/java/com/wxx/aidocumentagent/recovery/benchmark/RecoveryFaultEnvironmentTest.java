package com.wxx.aidocumentagent.recovery.benchmark;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RecoveryFaultEnvironmentTest {
    @Test void eachParallelPartitionUsesDistinctPorts() {
        assertThatCode(() -> RecoveryFaultEnvironment.validatePortRanges(13307, 16380, 8)).doesNotThrowAnyException();
    }
    @Test void overlappingRangesFailBeforeStartingAnyContainer() {
        assertThatThrownBy(() -> RecoveryFaultEnvironment.validatePortRanges(13307, 13308, 8)).hasMessage("PARALLEL_BENCHMARK_PORTS_OVERLAP");
    }
    @Test void shiftedPortCannotLeaveBenchmarkPortRange() {
        assertThatThrownBy(() -> RecoveryFaultEnvironment.validatePortRanges(59999, 16380, 8)).hasMessage("BENCHMARK_PORT_OUT_OF_RANGE");
    }
}
