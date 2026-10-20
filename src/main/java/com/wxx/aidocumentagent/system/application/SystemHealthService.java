package com.wxx.aidocumentagent.system.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.wxx.aidocumentagent.system.api.SystemHealthResponse;
import com.wxx.aidocumentagent.system.api.SystemHealthResponse.Status;

/** 有界并行检查和短期缓存，避免依赖故障占满请求线程。 */
public class SystemHealthService implements AutoCloseable {
    public static final List<String> COMPONENTS = List.of("database", "redis", "rabbitmq", "qdrant", "elasticsearch");
    private final Map<String, Probe> probes;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final ExecutorService executor = Executors.newFixedThreadPool(COMPONENTS.size(),
            Thread.ofPlatform().daemon().name("system-health-", 0).factory());
    private SystemHealthResponse cached;
    private long cachedUntil;

    public SystemHealthService(Map<String, Probe> probes, Duration timeout, Duration cacheTtl) {
        this.probes = Map.copyOf(probes);
        this.timeout = timeout;
        this.cacheTtl = cacheTtl;
    }

    public synchronized SystemHealthResponse health() {
        if (cached != null && System.nanoTime() - cachedUntil < 0) return cached;
        Map<String, Status> statuses = new LinkedHashMap<>();
        List<Callable<Status>> checks = new ArrayList<>();
        for (String name : COMPONENTS) checks.add(() -> probes.getOrDefault(name, () -> Status.UNKNOWN).check());
        try {
            var results = executor.invokeAll(checks, timeout.toMillis(), TimeUnit.MILLISECONDS);
            for (int i = 0; i < COMPONENTS.size(); i++) {
                Status status;
                try { status = results.get(i).isCancelled() ? Status.DOWN : results.get(i).get(); }
                catch (Exception exception) { status = Status.DOWN; }
                statuses.put(COMPONENTS.get(i), status == null ? Status.UNKNOWN : status);
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            COMPONENTS.forEach(name -> statuses.put(name, Status.UNKNOWN));
        }
        Status overall = statuses.containsValue(Status.DOWN) ? Status.DOWN
                : statuses.containsValue(Status.UNKNOWN) ? Status.UNKNOWN : Status.UP;
        cached = new SystemHealthResponse(overall, statuses);
        cachedUntil = System.nanoTime() + cacheTtl.toNanos();
        return cached;
    }

    @Override public void close() { executor.shutdownNow(); }
    @FunctionalInterface public interface Probe { Status check() throws Exception; }
}
