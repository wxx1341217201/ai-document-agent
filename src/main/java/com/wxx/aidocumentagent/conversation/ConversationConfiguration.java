package com.wxx.aidocumentagent.conversation;

import com.wxx.aidocumentagent.conversation.infrastructure.memory.ConversationMemoryStore;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.NoOpConversationMemoryStore;
import com.wxx.aidocumentagent.conversation.infrastructure.memory.RedisConversationMemoryStore;
import com.wxx.aidocumentagent.conversation.application.ConversationMessagePayloadCodec;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** M12 装配缓存端口。Redis 不可达时适配器降级为回源 MySQL，不影响审计数据的可靠性。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConversationMemoryProperties.class)
public class ConversationConfiguration {

    @Bean(name = "conversationTokenCountEstimator")
    TokenCountEstimator conversationTokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    @Bean
    ConversationMessagePayloadCodec conversationMessagePayloadCodec(ObjectMapper objectMapper) {
        return new ConversationMessagePayloadCodec(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ConversationMemoryStore.class)
    ConversationMemoryStore conversationMemoryStore(ObjectProvider<StringRedisTemplate> templates,
                                                     ObjectMapper objectMapper,
                                                     ConversationMemoryProperties properties) {
        // User configuration is parsed before Redis auto configuration. Resolve the provider at creation time.
        StringRedisTemplate template = templates.getIfAvailable();
        return template == null ? new NoOpConversationMemoryStore()
                : new RedisConversationMemoryStore(template, objectMapper, properties);
    }
}
