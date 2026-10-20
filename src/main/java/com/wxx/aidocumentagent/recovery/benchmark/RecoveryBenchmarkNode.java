package com.wxx.aidocumentagent.recovery.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wxx.aidocumentagent.agent.AgentConfiguration;
import com.wxx.aidocumentagent.agent.DocumentAgent;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.ExecutionConfiguration;
import com.wxx.aidocumentagent.agent.execution.ExecutionCheckpointObserver;
import com.wxx.aidocumentagent.agent.execution.application.*;
import com.wxx.aidocumentagent.agent.execution.api.ExecutionController;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.conversation.ConversationConfiguration;
import com.wxx.aidocumentagent.conversation.application.*;
import com.wxx.aidocumentagent.conversation.api.ConversationController;
import com.wxx.aidocumentagent.progress.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import tools.jackson.databind.ObjectMapper;

/** Separate, loopback-only child JVM. No fixture beans are enabled in the regular application. */
public final class RecoveryBenchmarkNode {
    private RecoveryBenchmarkNode() { }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.benchmark.recovery.node", havingValue = "true")
    @EnableAutoConfiguration
    @EntityScan("com.wxx.aidocumentagent")
    @EnableJpaRepositories("com.wxx.aidocumentagent")
    @Import({AgentConfiguration.class, ExecutionConfiguration.class, ExecutionStore.class, ExecutionRunner.class,
            ExecutionApplicationService.class, ExecutionRecoveryScanner.class, ExecutionController.class,
            ConversationConfiguration.class, ConversationApplicationService.class, ConversationMemoryService.class,
            ConversationTurnPersistenceService.class, ConversationMemoryCacheListener.class, ConversationController.class,
            ProgressWebSocketConfiguration.class, ProgressWebSocketHandler.class, ProgressWebSocketBroker.class,
            ProgressSubscriptionService.class, JdbcProgressSequenceStore.class, ProgressEmitter.class,
            GlobalExceptionHandler.class, TraceIdFilter.class, RecoveryFixtureController.class})
    public static class NodeConfiguration {
        @Bean(initMethod = "initialize") RecoveryFixtureService recoveryFixtureService(
                com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository knowledgeBases,
                com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository conversations,
                com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessageRepository messages,
                ConversationMemoryService memory, ConversationMessagePayloadCodec codec,
                org.springframework.data.redis.core.StringRedisTemplate redis, ExecutionStore executions,
                ObjectMapper mapper, org.springframework.core.env.Environment environment) {
            return new RecoveryFixtureService(knowledgeBases, conversations, messages, memory, codec, redis, executions, mapper, environment);
        }
        // Individual beans allow the existing ObjectProvider<DocumentAgent> registration to be reused.
        @Bean DocumentAgent recoveryRetrievalAgent(RecoveryFixtureService fixture) { return new RecoveryFixtureAgent(AgentMode.RETRIEVAL_FIRST, fixture); }
        @Bean DocumentAgent recoveryReactAgent(RecoveryFixtureService fixture) { return new RecoveryFixtureAgent(AgentMode.REACT, fixture); }
        @Bean DocumentAgent recoveryPlanAgent(RecoveryFixtureService fixture) { return new RecoveryFixtureAgent(AgentMode.PLAN_EXECUTE_REFLECT, fixture); }
        @Bean ExecutionCheckpointObserver recoveryCheckpointObserver(RecoveryFixtureService fixture) {
            return fixture::afterResultCheckpoint;
        }
    }

    public static void main(String[] args) throws Exception {
        // Skip unrelated model, vector, ES and Rabbit auto configurations: this benchmark exercises real state recovery.
        List<String> excluded = new ArrayList<>();
        var resources = RecoveryBenchmarkNode.class.getClassLoader().getResources(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        while (resources.hasMoreElements()) {
            try (var input = resources.nextElement().openStream()) {
                for (String raw : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    String name = raw.strip();
                    if (!name.isEmpty() && !name.startsWith("#") && (name.startsWith("org.springframework.ai.")
                            || name.contains(".amqp.") || name.contains(".elasticsearch."))) excluded.add(name);
                }
            }
        }
        List<String> boot = new ArrayList<>(List.of(args));
        boot.add("--spring.config.location=classpath:/application-recovery-benchmark.yml");
        boot.add("--spring.autoconfigure.exclude=" + String.join(",", excluded));
        boot.add("--app.benchmark.recovery.node=true");
        var context = new SpringApplication(NodeConfiguration.class).run(boot.toArray(String[]::new));
        var fixture = context.getBean(RecoveryFixtureService.class);
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        var info = new java.util.LinkedHashMap<>(fixture.metadata());
        info.put("port", context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
        Path ready = fixture.runtimeDirectory().resolve("ready-" + UUID.randomUUID() + ".json");
        Files.writeString(ready, mapper.writeValueAsString(info), StandardCharsets.UTF_8);
        Files.move(ready, fixture.runtimeDirectory().resolve("node-ready.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
