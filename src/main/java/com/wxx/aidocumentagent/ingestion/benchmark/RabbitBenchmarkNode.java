package com.wxx.aidocumentagent.ingestion.benchmark;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.chunking.*;
import com.wxx.aidocumentagent.chunking.application.DocumentChunkingApplicationService;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.document.application.*;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.parser.*;
import com.wxx.aidocumentagent.document.storage.*;
import com.wxx.aidocumentagent.ingestion.IngestionConfiguration;
import com.wxx.aidocumentagent.ingestion.application.*;
import com.wxx.aidocumentagent.ingestion.infrastructure.persistence.*;
import com.wxx.aidocumentagent.ingestion.messaging.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicit imports keep benchmark controllers, models and profiles out of the production application. */
public final class RabbitBenchmarkNode {
    private RabbitBenchmarkNode() { }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.benchmark.rabbitmq.node", havingValue = "true")
    @EnableAutoConfiguration
    @EntityScan("com.wxx.aidocumentagent")
    @EnableJpaRepositories("com.wxx.aidocumentagent")
    @EnableConfigurationProperties(ChunkingProperties.class)
    @Import({IngestionConfiguration.class, DocumentStorageProperties.class, LocalDocumentStorage.class,
            DocumentUploadValidator.class, DocumentApplicationService.class, DocumentIngestionRequestService.class,
            TxtDocumentParser.class, DocumentParserRegistry.class, FixedWindowChunkingStrategy.class,
            ChunkingStrategyRegistry.class, DocumentChunkingApplicationService.class,
            DocumentIngestionCoordinator.class, ChunkBatchContentReader.class, ChunkBatchIndexingApplicationService.class,
            IngestionOutboxLifecycleService.class, IngestionMessageCodec.class, GlobalExceptionHandler.class,
            RabbitBenchmarkController.class, RabbitBenchmarkApplicationService.class, AsyncConfiguration.class})
    public static class NodeConfiguration {
        @Bean RabbitBenchmarkMetrics rabbitBenchmarkMetrics() { return new RabbitBenchmarkMetrics(); }
        @Bean ChunkBatchTaskLifecycleService measuredBatchLifecycle(DocumentBatchTaskRepository batches,
                DocumentIngestionJobRepository jobs, DocumentRepository documents,
                DocumentIngestionOutboxEventRepository outbox, RabbitBenchmarkMetrics metrics) {
            return new MeasuredBatchLifecycle(batches, jobs, documents, outbox, metrics);
        }
        @Bean(initMethod = "initialize") LocalBenchmarkIndex localBenchmarkIndex(JdbcTemplate jdbc,
                ObjectMapper mapper, Environment env) {
            return new LocalBenchmarkIndex(jdbc, mapper,
                    env.getRequiredProperty("app.benchmark.rabbitmq.chunk-delay-ms", Integer.class),
                    env.getRequiredProperty("app.benchmark.rabbitmq.batch-delay-ms", Integer.class));
        }
        @Bean SerialBenchmarkIngestion serialBenchmarkIngestion(DocumentIngestionJobRepository jobs,
                DocumentBatchTaskRepository batches, JdbcTemplate jdbc, IngestionOutboxLifecycleService lifecycle,
                DocumentIngestionCoordinator coordinator, ChunkBatchIndexingApplicationService worker) {
            return new SerialBenchmarkIngestion(jobs, batches, jdbc, lifecycle, coordinator, worker);
        }
        @Bean RabbitBenchmarkRequestFilter rabbitBenchmarkRequestFilter(Environment env, RabbitBenchmarkMetrics metrics) {
            return new RabbitBenchmarkRequestFilter(env.getRequiredProperty("M19E_ACCESS_TOKEN"), metrics);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.benchmark.rabbitmq.mode", havingValue = "async")
    @Import({RabbitIngestionMessagePublisher.class, IngestionOutboxDispatcher.class,
            IngestionOutboxDispatchListener.class, IngestionPublicationGate.class, DocumentIngestionRabbitListener.class, ChunkBatchRabbitListener.class,
            IngestionFailureHandler.class, IngestionFailureClassifier.class, IngestionStaleProcessingRecovery.class,
            IngestionRecoveryScanner.class})
    public static class AsyncConfiguration { }

    public static void main(String[] args) throws Exception {
        String mode = List.of(args).stream().filter(a -> a.startsWith("--app.benchmark.rabbitmq.mode="))
                .map(a -> a.substring(a.indexOf('=') + 1)).findFirst().orElseThrow();
        if (!List.of("sync", "async").contains(mode)) throw new IllegalArgumentException("Invalid mode");
        var excluded = new ArrayList<String>();
        var resources = RabbitBenchmarkNode.class.getClassLoader().getResources(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        while (resources.hasMoreElements()) {
            try (var input = resources.nextElement().openStream()) {
                for (String raw : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    String name = raw.strip();
                    if (!name.isEmpty() && !name.startsWith("#") && (name.startsWith("org.springframework.ai.")
                            || name.contains(".elasticsearch.") || name.contains(".data.redis.")
                            || (mode.equals("sync") && name.contains(".amqp.")))) excluded.add(name);
                }
            }
        }
        var boot = new ArrayList<>(List.of(args));
        boot.add("--spring.config.location=classpath:/application-rabbitmq-benchmark.yml,classpath:/application-rabbitmq-benchmark-" + mode + ".yml");
        boot.add("--spring.autoconfigure.exclude=" + String.join(",", excluded));
        boot.add("--app.benchmark.rabbitmq.node=true");
        var context = new SpringApplication(NodeConfiguration.class).run(boot.toArray(String[]::new));
        var env = context.getEnvironment();
        Path runtime = Path.of(env.getRequiredProperty("app.benchmark.rabbitmq.runtime-directory"));
        Files.createDirectories(runtime);
        var ready = Map.of("runId", env.getRequiredProperty("app.benchmark.rabbitmq.run-id"), "mode", mode,
                "pid", ProcessHandle.current().pid(), "port", env.getRequiredProperty("local.server.port", Integer.class));
        Path temporary = runtime.resolve("node-ready.tmp");
        Files.writeString(temporary, context.getBean(ObjectMapper.class).writeValueAsString(ready));
        Files.move(temporary, runtime.resolve("node-ready.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
