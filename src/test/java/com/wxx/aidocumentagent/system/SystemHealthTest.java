package com.wxx.aidocumentagent.system;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.system.api.SystemController;
import com.wxx.aidocumentagent.system.api.SystemHealthResponse.Status;
import com.wxx.aidocumentagent.system.application.SystemHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SystemHealthTest {
    @Test
    void returnsOnlyAllowlistedStatusesAndCachesConcurrentDependencyChecks() throws Exception {
        var calls = new AtomicInteger();
        Map<String, SystemHealthService.Probe> probes = new LinkedHashMap<>();
        SystemHealthService.COMPONENTS.forEach(name -> probes.put(name, () -> { calls.incrementAndGet(); return Status.UP; }));
        probes.put("private-host", () -> { throw new AssertionError("must not be called"); });
        try (var service = new SystemHealthService(probes, Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            var mvc = MockMvcBuilders.standaloneSetup(new SystemController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).addFilter(new TraceIdFilter()).build();
            mvc.perform(get("/api/v1/system/health").header("X-Trace-Id", "safe-health"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.status").value("UP"))
                    .andExpect(jsonPath("$.data.components.length()").value(5))
                    .andExpect(jsonPath("$.data.components.qdrant").value("UP"))
                    .andExpect(jsonPath("$.data.components.private-host").doesNotExist());
            service.health();
            assertThat(calls.get()).isEqualTo(5);
        }
    }

    @Test
    void oneFailedProbeReturnsUsableDegradedResultWithoutExceptionDetails() throws Exception {
        try (var service = new SystemHealthService(Map.of("redis", () -> { throw new Exception("redis://secret-password@private"); }),
                Duration.ofSeconds(1), Duration.ZERO)) {
            var mvc = MockMvcBuilders.standaloneSetup(new SystemController(service)).addFilter(new TraceIdFilter()).build();
            var response = mvc.perform(get("/api/v1/system/health"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DOWN"))
                    .andExpect(jsonPath("$.data.components.redis").value("DOWN"))
                    .andExpect(jsonPath("$.data.components.database").value("UNKNOWN")).andReturn();
            assertThat(response.getResponse().getContentAsString()).doesNotContain("secret-password", "private");
        }
    }

    @Test
    void hungProbeIsBoundedAndOnlyMarksThatComponentDown() {
        try (var service = new SystemHealthService(Map.of("database", () -> { Thread.sleep(10_000); return Status.UP; },
                "redis", () -> Status.UP), Duration.ofMillis(100), Duration.ZERO)) {
            long started = System.nanoTime();
            var result = service.health();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(result.components()).containsEntry("database", Status.DOWN).containsEntry("redis", Status.UP);
        }
    }
}
