package com.wxx.aidocumentagent.rag.infrastructure.chat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import static org.assertj.core.api.Assertions.assertThat;

class RagChatOptionsTest {
    @Test
    void deepSeekOptionsKeepThinkingControlAlongsideStructuredOutputAndBudget() {
        var options = new RagChatOptions("https://api.deepseek.com/v1", true).options(1_024, true).build();
        assertThat(options.getMaxTokens()).isEqualTo(1_024);
        assertThat(options.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.getExtraBody()).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void vendorParametersAreOptOutAndNeverAppliedToOtherOrLookalikeEndpoints() {
        for (String endpoint : new String[]{"https://api.openai.com", "https://api.deepseek.com.example.net", "invalid URL"}) {
            var policy = new RagChatOptions(endpoint, true);
            assertThat(policy.thinkingDisabled()).isFalse();
            assertThat(policy.options(null, false).build().getExtraBody()).isNullOrEmpty();
        }
        assertThat(new RagChatOptions("https://api.deepseek.com", false).thinkingDisabled()).isFalse();
    }
}
