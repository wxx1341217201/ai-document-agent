package com.wxx.aidocumentagent.agent.react;

import com.wxx.aidocumentagent.agent.AgentConversationSupport;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** 预算截断仍保留精确原文前缀，模型输入和证据快照消费同一份文本。 */
final class EvidenceTokenBudget {
    private static final TokenCountEstimator ESTIMATOR = new JTokkitTokenCountEstimator();
    private EvidenceTokenBudget() { }
    static int tokens(String text) { return ESTIMATOR.estimate(text); }

    static String prefix(String text, int tokens) {
        if (tokens <= 0) return "";
        if (tokens(text) <= tokens) return text;
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (tokens(AgentConversationSupport.prefix(text, middle)) <= tokens) low = middle;
            else high = middle - 1;
        }
        return AgentConversationSupport.prefix(text, low);
    }
}
