package com.wxx.aidocumentagent.chunking;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.chunking.domain.ChunkingErrorCode;
import com.wxx.aidocumentagent.common.api.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 在启动时拒绝策略名称冲突，并按配置名称选择实现。
 */
@Component
public final class ChunkingStrategyRegistry {

    private final Map<String, ChunkingStrategy> strategies;

    public ChunkingStrategyRegistry(List<ChunkingStrategy> strategyImplementations) {
        Map<String, ChunkingStrategy> registeredStrategies = new HashMap<>();
        for (ChunkingStrategy strategy : strategyImplementations) {
            String name = normalizeName(strategy.name());
            ChunkingStrategy existing = registeredStrategies.putIfAbsent(name, strategy);
            if (existing != null) {
                throw new IllegalStateException("同一名称不能注册多个切分策略: " + name);
            }
        }
        this.strategies = Map.copyOf(registeredStrategies);
    }

    public ChunkingStrategy strategyFor(String name) {
        ChunkingStrategy strategy = strategies.get(normalizeName(name));
        if (strategy == null) {
            throw new BusinessException(ChunkingErrorCode.CHUNKING_STRATEGY_NOT_FOUND);
        }
        return strategy;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ChunkingErrorCode.CHUNKING_STRATEGY_NOT_FOUND);
        }
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
