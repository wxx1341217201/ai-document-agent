package com.wxx.aidocumentagent.progress;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.progress", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableWebSocket
@EnableConfigurationProperties(ProgressProperties.class)
public class ProgressWebSocketConfiguration implements WebSocketConfigurer {
    private final ProgressWebSocketHandler handler;
    private final ProgressProperties properties;

    public ProgressWebSocketConfiguration(ProgressWebSocketHandler handler, ProgressProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/progress")
                .addInterceptors(new ProgressHandshakeInterceptor(properties));
    }

    @Bean("progressSender")
    static ThreadPoolTaskExecutor progressSender(ProgressProperties settings) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.getSenderThreads());
        executor.setMaxPoolSize(settings.getSenderThreads());
        executor.setQueueCapacity(settings.getSenderQueueCapacity());
        executor.setThreadNamePrefix("progress-sender-");
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean(ProgressSubscriptionAuthorizer.class)
    static ProgressSubscriptionAuthorizer progressSubscriptionAuthorizer() {
        return (subscriber, request) -> subscriber != null && subscriber.sharedTokenAuthenticated();
    }
}
