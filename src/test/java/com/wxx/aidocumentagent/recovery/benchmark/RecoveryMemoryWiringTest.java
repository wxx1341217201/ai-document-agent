package com.wxx.aidocumentagent.recovery.benchmark;

import com.wxx.aidocumentagent.conversation.ConversationConfiguration;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryMemoryWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConversationConfiguration.class, JsonConfiguration.class);
    @Configuration(proxyBeanMethods = false)
    static class JsonConfiguration { @Bean ObjectMapper mapper() { return new JsonMapper(); } }

    @Test void realRedisAutoConfigurationSelectsRedisMemoryStoreAfterUserConfigurationParsing() {
        runner.withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConversationMemoryStore.class);
            assertThat(context.getBean(ConversationMemoryStore.class)).isInstanceOf(RedisConversationMemoryStore.class);
        });
    }
    @Test void noRedisInfrastructureExplicitlyUsesNoOpFallback() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ConversationMemoryStore.class)).isInstanceOf(NoOpConversationMemoryStore.class);
        });
    }
}
