package com.wxx.aidocumentagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class RagConfigurationOptionsTest {
    @Test
    void chatEndpointOverridesTheGlobalEndpointForVendorOptions() {
        var environment = new MockEnvironment().withProperty("spring.ai.openai.base-url", "https://api.openai.com")
                .withProperty("spring.ai.openai.chat.base-url", "https://api.deepseek.com/v1");
        var properties = new RagProperties();
        var configuration = new RagConfiguration();
        assertThat(configuration.ragChatOptions(properties, environment).thinkingDisabled()).isTrue();
        properties.setDisableThinking(false);
        assertThat(configuration.ragChatOptions(properties, environment).thinkingDisabled()).isFalse();
    }
}
