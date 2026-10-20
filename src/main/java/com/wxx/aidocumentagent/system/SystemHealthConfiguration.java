package com.wxx.aidocumentagent.system;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.qdrant.client.QdrantClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import com.wxx.aidocumentagent.system.api.SystemHealthResponse.Status;
import com.wxx.aidocumentagent.system.application.SystemHealthService;

@Configuration(proxyBeanMethods = false)
public class SystemHealthConfiguration {
    @Bean(destroyMethod = "close")
    SystemHealthService systemHealthService(ObjectProvider<DataSource> database,
            ObjectProvider<RedisConnectionFactory> redis, ObjectProvider<RabbitTemplate> rabbit,
            ObjectProvider<QdrantClient> qdrant, ObjectProvider<ElasticsearchClient> elasticsearch) {
        return new SystemHealthService(Map.of(
                "database", probe(database, source -> {
                    try (var connection = source.getConnection()) { return connection.isValid(2); }
                }),
                "redis", probe(redis, factory -> {
                    try (var connection = factory.getConnection()) { return "PONG".equalsIgnoreCase(connection.ping()); }
                }),
                "rabbitmq", probe(rabbit, template -> Boolean.TRUE.equals(template.execute(channel -> channel.isOpen()))),
                "qdrant", probe(qdrant, client -> {
                    var request = client.healthCheckAsync(Duration.ofSeconds(2));
                    try { request.get(2, TimeUnit.SECONDS); return true; }
                    finally { if (!request.isDone()) request.cancel(true); }
                }),
                "elasticsearch", probe(elasticsearch, client -> client.ping().value())),
                Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    private <T> SystemHealthService.Probe probe(ObjectProvider<T> provider, Check<T> check) {
        return () -> {
            T client = provider.getIfAvailable();
            return client == null ? Status.UNKNOWN : check.healthy(client) ? Status.UP : Status.DOWN;
        };
    }
    @FunctionalInterface private interface Check<T> { boolean healthy(T client) throws Exception; }
}
