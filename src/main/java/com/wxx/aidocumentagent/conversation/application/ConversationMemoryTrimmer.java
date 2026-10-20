package com.wxx.aidocumentagent.conversation.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.wxx.aidocumentagent.conversation.ConversationMemoryProperties;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryEntry;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** 按“最近优先”策略裁剪 Redis 上下文，并保证返回内容不超过消息数和 token 双上限。 */
public final class ConversationMemoryTrimmer {

    private final ConversationMemoryProperties properties;
    private final TokenCountEstimator tokenCountEstimator;

    public ConversationMemoryTrimmer(ConversationMemoryProperties properties, TokenCountEstimator tokenCountEstimator) {
        this.properties = Objects.requireNonNull(properties, "conversationMemoryProperties不能为空");
        this.tokenCountEstimator = Objects.requireNonNull(tokenCountEstimator, "conversationTokenCountEstimator不能为空");
    }

    public List<ConversationMemoryEntry> trim(List<ConversationMemoryEntry> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<ConversationMemoryEntry> ordered = source.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ConversationMemoryEntry::sequenceNo))
                .toList();
        List<ConversationMemoryEntry> retainedNewestFirst = new ArrayList<>();
        int remainingTokens = properties.getMaxMemoryTokens();
        for (int index = ordered.size() - 1; index >= 0 && retainedNewestFirst.size() < properties.getMaxMemoryMessages();
             index--) {
            ConversationMemoryEntry candidate = bounded(ordered.get(index), remainingTokens);
            if (candidate == null) {
                break;
            }
            retainedNewestFirst.add(candidate);
            remainingTokens -= estimate(candidate.content());
            if (remainingTokens <= 0) {
                break;
            }
        }
        List<ConversationMemoryEntry> result = new ArrayList<>(retainedNewestFirst);
        result.sort(Comparator.comparingLong(ConversationMemoryEntry::sequenceNo));
        return List.copyOf(result);
    }

    private ConversationMemoryEntry bounded(ConversationMemoryEntry candidate, int remainingTokens) {
        if (remainingTokens <= 0) {
            return null;
        }
        if (estimate(candidate.content()) <= remainingTokens) {
            return candidate;
        }
        String truncated = truncateToTokenBudget(candidate.content(), remainingTokens);
        return truncated == null || truncated.isBlank() ? null
                : new ConversationMemoryEntry(candidate.sequenceNo(), candidate.role(), truncated);
    }

    private String truncateToTokenBudget(String content, int tokenBudget) {
        int maximum = content.codePointCount(0, content.length());
        int low = 1;
        int high = maximum;
        int best = 0;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            String candidate = content.substring(0, content.offsetByCodePoints(0, middle));
            if (estimate(candidate) <= tokenBudget) {
                best = middle;
                low = middle + 1;
            }
            else {
                high = middle - 1;
            }
        }
        return best == 0 ? null : content.substring(0, content.offsetByCodePoints(0, best));
    }

    private int estimate(String content) {
        return Math.max(0, tokenCountEstimator.estimate(content));
    }
}
