package com.wxx.aidocumentagent.ingestion;

import java.util.Map;
import java.util.concurrent.Executor;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingProcessor;
import com.wxx.aidocumentagent.ingestion.application.NoOpChunkBatchIndexingProcessor;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfiguration {

    @Bean("ingestionDispatchExecutor")
    TaskExecutor ingestionDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ingestion-outbox-");
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.keyword", name = "enabled", havingValue = "false")
    @ConditionalOnMissingBean(ChunkBatchIndexingProcessor.class)
    ChunkBatchIndexingProcessor noOpChunkBatchIndexingProcessor() {
        return new NoOpChunkBatchIndexingProcessor();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "app.ingestion", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RabbitTopologyConfiguration {

        @Bean
        DirectExchange documentIngestionExchange(IngestionProperties properties) {
            return new DirectExchange(properties.getMessaging().getDocumentExchange(), true, false);
        }

        @Bean
        DirectExchange documentIngestionRetryExchange(IngestionProperties properties) {
            return new DirectExchange(properties.getMessaging().getDocumentRetryExchange(), true, false);
        }

        @Bean
        DirectExchange documentIngestionDeadLetterExchange(IngestionProperties properties) {
            return new DirectExchange(properties.getMessaging().getDocumentDeadLetterExchange(), true, false);
        }

        @Bean
        Queue documentIngestionQueue(IngestionProperties properties) {
            IngestionProperties.Messaging messaging = properties.getMessaging();
            return new Queue(messaging.getDocumentQueue(), true, false, false, Map.of(
                    "x-dead-letter-exchange", messaging.getDocumentDeadLetterExchange(),
                    "x-dead-letter-routing-key", messaging.getDocumentDeadLetterRoutingKey()));
        }

        @Bean
        Queue documentIngestionRetryQueue(IngestionProperties properties) {
            IngestionProperties.Messaging messaging = properties.getMessaging();
            return new Queue(messaging.getDocumentRetryQueue(), true, false, false, Map.of(
                    "x-dead-letter-exchange", messaging.getDocumentExchange(),
                    "x-dead-letter-routing-key", messaging.getDocumentRoutingKey()));
        }

        @Bean
        Queue documentIngestionDeadLetterQueue(IngestionProperties properties) {
            return new Queue(properties.getMessaging().getDocumentDeadLetterQueue(), true);
        }

        @Bean
        Binding documentIngestionBinding(Queue documentIngestionQueue, DirectExchange documentIngestionExchange,
                                         IngestionProperties properties) {
            return BindingBuilder.bind(documentIngestionQueue).to(documentIngestionExchange)
                    .with(properties.getMessaging().getDocumentRoutingKey());
        }

        @Bean
        Binding documentIngestionRetryBinding(Queue documentIngestionRetryQueue,
                                              DirectExchange documentIngestionRetryExchange,
                                              IngestionProperties properties) {
            return BindingBuilder.bind(documentIngestionRetryQueue).to(documentIngestionRetryExchange)
                    .with(properties.getMessaging().getDocumentRetryRoutingKey());
        }

        @Bean
        Binding documentIngestionDeadLetterBinding(Queue documentIngestionDeadLetterQueue,
                                                   DirectExchange documentIngestionDeadLetterExchange,
                                                   IngestionProperties properties) {
            return BindingBuilder.bind(documentIngestionDeadLetterQueue).to(documentIngestionDeadLetterExchange)
                    .with(properties.getMessaging().getDocumentDeadLetterRoutingKey());
        }

        @Bean
        DirectExchange documentBatchExchange(IngestionProperties properties) {
            return new DirectExchange(properties.getMessaging().getBatchExchange(), true, false);
        }

        @Bean
        DirectExchange documentBatchRetryExchange(IngestionProperties properties) {
            return new DirectExchange(properties.getMessaging().getBatchRetryExchange(), true, false);
        }

        @Bean
        DirectExchange documentBatchDeadLetterExchange(IngestionProperties properties) {
            return new DirectExchange(properties.getMessaging().getBatchDeadLetterExchange(), true, false);
        }

        @Bean
        Queue documentBatchQueue(IngestionProperties properties) {
            IngestionProperties.Messaging messaging = properties.getMessaging();
            return new Queue(messaging.getBatchQueue(), true, false, false, Map.of(
                    "x-dead-letter-exchange", messaging.getBatchDeadLetterExchange(),
                    "x-dead-letter-routing-key", messaging.getBatchDeadLetterRoutingKey()));
        }

        @Bean
        Queue documentBatchRetryQueue(IngestionProperties properties) {
            IngestionProperties.Messaging messaging = properties.getMessaging();
            return new Queue(messaging.getBatchRetryQueue(), true, false, false, Map.of(
                    "x-dead-letter-exchange", messaging.getBatchExchange(),
                    "x-dead-letter-routing-key", messaging.getBatchRoutingKey()));
        }

        @Bean
        Queue documentBatchDeadLetterQueue(IngestionProperties properties) {
            return new Queue(properties.getMessaging().getBatchDeadLetterQueue(), true);
        }

        @Bean
        Binding documentBatchBinding(Queue documentBatchQueue, DirectExchange documentBatchExchange,
                                     IngestionProperties properties) {
            return BindingBuilder.bind(documentBatchQueue).to(documentBatchExchange)
                    .with(properties.getMessaging().getBatchRoutingKey());
        }

        @Bean
        Binding documentBatchRetryBinding(Queue documentBatchRetryQueue, DirectExchange documentBatchRetryExchange,
                                          IngestionProperties properties) {
            return BindingBuilder.bind(documentBatchRetryQueue).to(documentBatchRetryExchange)
                    .with(properties.getMessaging().getBatchRetryRoutingKey());
        }

        @Bean
        Binding documentBatchDeadLetterBinding(Queue documentBatchDeadLetterQueue,
                                               DirectExchange documentBatchDeadLetterExchange,
                                               IngestionProperties properties) {
            return BindingBuilder.bind(documentBatchDeadLetterQueue).to(documentBatchDeadLetterExchange)
                    .with(properties.getMessaging().getBatchDeadLetterRoutingKey());
        }

        @Bean("ingestionRabbitListenerContainerFactory")
        SimpleRabbitListenerContainerFactory ingestionRabbitListenerContainerFactory(
                ConnectionFactory connectionFactory, IngestionProperties properties) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
            factory.setDefaultRequeueRejected(false);
            factory.setPrefetchCount(properties.getPrefetch());
            factory.setConcurrentConsumers(properties.getListenerConcurrency());
            factory.setMaxConcurrentConsumers(properties.getListenerMaxConcurrency());
            return factory;
        }
    }
}
