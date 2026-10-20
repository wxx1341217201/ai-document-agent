package com.wxx.aidocumentagent.agent.plan;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wxx.aidocumentagent.agent.react.ToolRegistry;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlanConfiguration {
    @Bean(name = "planWorkers", destroyMethod = "close")
    ExecutorService planWorkers() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    Planner planner(ObjectMapper mapper, PlanProperties properties) {
        return new Planner(mapper, new PlanValidator(properties.getMaxSteps()));
    }

    @Bean
    Reflector reflector() { return new Reflector(); }

    @Bean
    PlanModelClient planModelClient(@Qualifier("ragSpringAiChatClient") ChatClient client,
                                   PlanProperties properties, RagChatOptions options) {
        return new SpringAiPlanModelClient(client, properties, options);
    }

    @Bean
    Executor planExecutor(ToolRegistry tools, ObjectMapper mapper, PlanProperties properties,
                          @Qualifier("planWorkers") ExecutorService workers) {
        return new Executor(tools, mapper, properties, workers);
    }

    @Bean
    PlanExecuteReflectAgent planExecuteReflectAgent(KnowledgeBaseRepository knowledgeBases,
                                                     ConversationRepository conversations, Planner planner,
                                                     Executor executor, Reflector reflector,
                                                     PlanModelClient model, PlanProperties properties,
                                                     @Qualifier("planWorkers") ExecutorService workers) {
        return new PlanExecuteReflectAgent(knowledgeBases, conversations, planner, executor, reflector,
                model, properties, workers);
    }
}
