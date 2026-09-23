package com.wxx.aidocumentagent.rag.infrastructure.chat;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.application.RagChatClient;
import com.wxx.aidocumentagent.rag.application.RagChatCompletion;
import com.wxx.aidocumentagent.rag.application.RagChatException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Spring AI ChatClient 适配器；使用虚拟线程隔离阻塞调用并在应用层执行总请求超时。 */
public final class SpringAiRagChatClient implements RagChatClient {

    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final RagProperties properties;

    public SpringAiRagChatClient(ChatClient chatClient, ExecutorService executor, RagProperties properties) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient不能为空");
        this.executor = Objects.requireNonNull(executor, "ragChatExecutor不能为空");
        this.properties = Objects.requireNonNull(properties, "ragProperties不能为空");
    }

    @Override
    public RagChatCompletion complete(String systemInstruction, String userPrompt) {
        Future<ChatResponse> task = executor.submit(() -> chatClient.prompt()
                .system(systemInstruction)
                .user(userPrompt)
                .call()
                .chatResponse());
        try {
            ChatResponse response = task.get(properties.getChatTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return toCompletion(response);
        }
        catch (TimeoutException exception) {
            task.cancel(true);
            throw new RagChatException(RagChatException.Reason.TIMEOUT, exception);
        }
        catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new RagChatException(RagChatException.Reason.CALL_FAILED, exception);
        }
        catch (ExecutionException exception) {
            throw new RagChatException(RagChatException.Reason.CALL_FAILED, exception.getCause());
        }
        catch (RagChatException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw new RagChatException(RagChatException.Reason.CALL_FAILED, exception);
        }
    }

    private RagChatCompletion toCompletion(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new RagChatException(RagChatException.Reason.INVALID_RESPONSE, null);
        }
        String answer = response.getResult().getOutput().getText();
        if (answer == null || answer.isBlank()) {
            throw new RagChatException(RagChatException.Reason.INVALID_RESPONSE, null);
        }
        ChatResponseMetadata metadata = response.getMetadata();
        String modelName = metadata != null && hasText(metadata.getModel()) ? metadata.getModel() : properties.getModel();
        Usage usage = metadata == null ? null : metadata.getUsage();
        return new RagChatCompletion(answer, modelName, tokenCount(usage == null ? null : usage.getPromptTokens()),
                tokenCount(usage == null ? null : usage.getCompletionTokens()),
                tokenCount(usage == null ? null : usage.getTotalTokens()));
    }

    private Integer tokenCount(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
