package com.wxx.aidocumentagent.agent.plan;

import java.util.Objects;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** 复用项目已配置连接、读取和请求超时的 Spring AI ChatClient。 */
public final class SpringAiPlanModelClient implements PlanModelClient {
    private final ChatClient client;
    private final PlanProperties properties;
    private final RagChatOptions options;

    public SpringAiPlanModelClient(ChatClient client) {
        this(client, new PlanProperties(), new RagChatOptions("https://api.openai.com", false));
    }

    public SpringAiPlanModelClient(ChatClient client, PlanProperties properties, RagChatOptions options) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
        this.options = Objects.requireNonNull(options);
    }

    @Override
    public Reply complete(String system, String user) {
        return complete(Phase.SYNTHESIZE, system, user);
    }

    @Override
    public Reply complete(Phase phase, String system, String user) {
        boolean planning = phase != Phase.SYNTHESIZE;
        ChatResponse response = client.prompt().system(system).user(user)
                .options(options.options(planning ? properties.getPlanningOutputTokens() : properties.getSynthesisOutputTokens(),
                        properties.isStructuredAnswers() || phase == Phase.VERIFY || planning && properties.isJsonPlanning())).call().chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new Reply(null, null, null, null, null);
        }
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        return new Reply(response.getResult().getOutput().getText(),
                metadata == null ? null : metadata.getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens());
    }
}
