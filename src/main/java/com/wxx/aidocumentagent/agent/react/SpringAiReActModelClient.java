package com.wxx.aidocumentagent.agent.react;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

/** 使用 Spring AI 的工具定义和消息协议；关闭自动循环以便逐轮实施应用层预算。 */
public final class SpringAiReActModelClient implements ReActModelClient {

    private final ChatClient client;
    private final ReActProperties properties;
    private final RagChatOptions options;

    public SpringAiReActModelClient(ChatClient client) {
        this(client, new ReActProperties(), new RagChatOptions("https://api.openai.com", false));
    }

    public SpringAiReActModelClient(ChatClient client, ReActProperties properties, RagChatOptions options) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
        this.options = Objects.requireNonNull(options);
    }

    @Override
    public ChatResponse complete(List<Message> messages, ToolCallback[] tools) {
        var requestOptions = options.options(properties.getMaxOutputTokens(), properties.isStructuredAnswers() && tools.length == 0);
        boolean firstSearch = properties.isRequireInitialSearch()
                && messages.stream().noneMatch(ToolResponseMessage.class::isInstance)
                && java.util.Arrays.stream(tools).anyMatch(tool -> tool.getToolDefinition().name().equals("searchKnowledgeBase"));
        if (firstSearch) requestOptions.toolChoice(Map.of("type", "function",
                "function", Map.of("name", "searchKnowledgeBase")));
        return client.prompt().messages(messages).options(requestOptions)
                .tools((Object[]) tools)
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .call().chatResponse();
    }
}
