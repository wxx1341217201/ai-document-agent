package com.wxx.aidocumentagent.rag.infrastructure.chat;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.agent.ModelFailureDiagnostics;
import com.wxx.aidocumentagent.rag.application.RagChatClient;
import com.wxx.aidocumentagent.rag.application.RagChatCompletion;
import com.wxx.aidocumentagent.rag.application.RagChatException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spring AI ChatClient 适配器；使用虚拟线程隔离阻塞调用并在应用层执行总请求超时。 */
public final class SpringAiRagChatClient implements RagChatClient {
    private static final Logger log = LoggerFactory.getLogger(SpringAiRagChatClient.class);

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
            var failure = ModelFailureDiagnostics.classify(exception.getCause());
            log.warn("rag_model_failure category={} httpStatus={} retryable={}",
                    failure.category(), failure.httpStatus(), failure.retryable());
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
            log.warn("rag_model_failure category=EMPTY_RESPONSE");
            throw new RagChatException(RagChatException.Reason.INVALID_RESPONSE, null);
        }
        String answer = visibleAnswer(response.getResult().getOutput().getText());
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

    /**
     * 某些兼容接口会把供应商的推理片段拼进文本。仅剥离位于输出开头的标准 think 块；
     * 未闭合或只有推理的输出视为无效，确保其既不会返回，也不会进入会话审计表。
     */
    private String visibleAnswer(String answer) {
        if (answer == null) {
            return null;
        }
        String normalized = answer.strip();
        if (!normalized.regionMatches(true, 0, "<think>", 0, "<think>".length())) {
            return normalized;
        }
        int closingIndex = indexOfIgnoreCase(normalized, "</think>", "<think>".length());
        if (closingIndex < 0) {
            throw new RagChatException(RagChatException.Reason.INVALID_RESPONSE, null);
        }
        String visible = normalized.substring(closingIndex + "</think>".length()).strip();
        if (visible.isBlank()) {
            throw new RagChatException(RagChatException.Reason.INVALID_RESPONSE, null);
        }
        return visible;
    }

    private int indexOfIgnoreCase(String source, String target, int fromIndex) {
        int lastPossibleIndex = source.length() - target.length();
        for (int index = fromIndex; index <= lastPossibleIndex; index++) {
            if (source.regionMatches(true, index, target, 0, target.length())) {
                return index;
            }
        }
        return -1;
    }
}
