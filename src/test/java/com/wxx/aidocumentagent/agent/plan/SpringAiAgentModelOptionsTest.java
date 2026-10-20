package com.wxx.aidocumentagent.agent.plan;

import java.util.List;
import java.util.Map;

import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.agent.react.SpringAiReActModelClient;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiAgentModelOptionsTest {
    @Test
    void firstReactCallRequiresFreshSearchAndLaterCallsCanChooseFreely() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("searchKnowledgeBase")
                .description("search").inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        var client = new SpringAiReActModelClient(ChatClient.create(model), new ReActProperties(),
                new RagChatOptions("https://api.deepseek.com", true));
        client.complete(List.of(new UserMessage("follow-up question")), new ToolCallback[]{callback});
        client.complete(List.of(new UserMessage("question"), ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call", "searchKnowledgeBase", "result"))).build()),
                new ToolCallback[]{callback});
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(captor.capture());
        assertThat(((OpenAiChatOptions) captor.getAllValues().get(0).getOptions()).getToolChoice())
                .isEqualTo(Map.of("type", "function", "function", Map.of("name", "searchKnowledgeBase")));
        assertThat(((OpenAiChatOptions) captor.getAllValues().get(1).getOptions()).getToolChoice()).isNull();
    }

    @Test
    void adaptersSendPhaseSpecificJsonAndOutputLimitsWithoutLosingProviderOptions() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        ChatClient client = ChatClient.create(model);
        var policy = new RagChatOptions("https://api.deepseek.com/v1", true);
        var properties = new PlanProperties();
        var plan = new SpringAiPlanModelClient(client, properties, policy);
        plan.complete(PlanModelClient.Phase.PLAN, "JSON", "question");
        plan.complete(PlanModelClient.Phase.REPAIR, "JSON", "repair");
        plan.complete(PlanModelClient.Phase.SYNTHESIZE, "answer", "question");
        plan.complete(PlanModelClient.Phase.VERIFY, "JSON", "coverage");
        var reactProperties = new ReActProperties();
        new SpringAiReActModelClient(client, reactProperties, policy)
                .complete(List.of(new UserMessage("question")), new ToolCallback[0]);
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(5)).call(captor.capture());
        var options = captor.getAllValues().stream().map(prompt -> (OpenAiChatOptions) prompt.getOptions()).toList();
        assertThat(options).allSatisfy(option -> assertThat(option.getExtraBody())
                .containsEntry("thinking", Map.of("type", "disabled")));
        assertThat(options.subList(0, 2)).allSatisfy(option -> {
            assertThat(option.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
            assertThat(option.getMaxTokens()).isEqualTo(properties.getPlanningOutputTokens());
        });
        assertThat(options.get(2).getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.TEXT);
        assertThat(options.get(2).getMaxTokens()).isEqualTo(properties.getSynthesisOutputTokens());
        assertThat(options.get(3).getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.get(3).getMaxTokens()).isEqualTo(properties.getPlanningOutputTokens());
        assertThat(options.get(4).getMaxTokens()).isEqualTo(reactProperties.getMaxOutputTokens());
    }
}
