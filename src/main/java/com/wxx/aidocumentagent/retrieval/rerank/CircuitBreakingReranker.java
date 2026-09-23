package com.wxx.aidocumentagent.retrieval.rerank;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.wxx.aidocumentagent.retrieval.RetrievedChunk;

/**
 * 轻量级、进程内熔断器：仅连续可重试的远端失败会打开熔断；成功请求会恢复闭合状态。
 * 熔断开启期间不调用委托 HTTP 客户端，由应用服务统一回退到 RRF。
 */
public final class CircuitBreakingReranker implements Reranker {

    private final Reranker delegate;
    private final RerankingProperties properties;
    private final Clock clock;
    private final AtomicInteger consecutiveRetryableFailures = new AtomicInteger();
    private final AtomicLong openUntilEpochMillis = new AtomicLong();

    public CircuitBreakingReranker(Reranker delegate, RerankingProperties properties) {
        this(delegate, properties, Clock.systemUTC());
    }

    CircuitBreakingReranker(Reranker delegate, RerankingProperties properties, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate不能为空");
        this.properties = Objects.requireNonNull(properties, "rerankingProperties不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    @Override
    public List<RankedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (isOpen()) {
            throw new RerankingException(RerankingErrorCode.CIRCUIT_OPEN);
        }
        try {
            List<RankedChunk> result = delegate.rerank(query, candidates, topN);
            consecutiveRetryableFailures.set(0);
            openUntilEpochMillis.set(0L);
            return result;
        }
        catch (RerankingException exception) {
            if (exception.isRetryable() && exception.getErrorCode() != RerankingErrorCode.CIRCUIT_OPEN) {
                recordRetryableFailure();
            }
            throw exception;
        }
    }

    private boolean isOpen() {
        long now = clock.millis();
        long openUntil = openUntilEpochMillis.get();
        if (openUntil <= now) {
            if (openUntil > 0L) {
                openUntilEpochMillis.compareAndSet(openUntil, 0L);
                consecutiveRetryableFailures.set(0);
            }
            return false;
        }
        return true;
    }

    private void recordRetryableFailure() {
        int failures = consecutiveRetryableFailures.incrementAndGet();
        if (failures >= properties.getCircuitFailureThreshold()) {
            long openUntil = clock.millis() + properties.getCircuitOpenDuration().toMillis();
            openUntilEpochMillis.accumulateAndGet(openUntil, Math::max);
        }
    }
}
