package com.wxx.aidocumentagent.reading;

import java.time.Duration;
import com.wxx.aidocumentagent.rag.infrastructure.chat.RagChatOptions;
import org.springframework.ai.chat.client.ChatClient;

/** SDK retries are disabled; every actual generation must acquire the root permit. */
final class SpringAiReadingModel implements ReadingModel {
    private final ChatClient client;
    private final RagChatOptions options;
    SpringAiReadingModel(ChatClient client, RagChatOptions options) { this.client = client; this.options = options; }
    public Reply complete(String phase, String system, String user) {
        if (system.length() + user.length() > 32_000) throw new ReadingBudget.Exhausted("本轮证据窗口已满");
        var response = client.prompt().system(system).user(user)
                .options(options.options(2_048, true).maxRetries(0).timeout(Duration.ofSeconds(35))).call().chatResponse();
        if (response == null || response.getResult() == null) throw new IllegalStateException("Empty response");
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new Reply(response.getResult().getOutput().getText(),
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }
}
