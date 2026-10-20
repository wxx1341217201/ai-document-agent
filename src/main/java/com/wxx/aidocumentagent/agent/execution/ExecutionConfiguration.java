package com.wxx.aidocumentagent.agent.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfiguration {
    @Bean("executionWorker")
    @ConditionalOnProperty(prefix = "app.agent.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskExecutor executionWorker(ExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("agent-execution-");
        executor.initialize();
        return executor;
    }
}
