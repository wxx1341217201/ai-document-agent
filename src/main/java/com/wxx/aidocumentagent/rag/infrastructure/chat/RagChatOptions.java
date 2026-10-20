package com.wxx.aidocumentagent.rag.infrastructure.chat;

import java.net.URI;
import java.util.Map;

import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 仅对 DeepSeek 官方端点发送供应商参数；每个阶段重新保留这些选项。 */
public final class RagChatOptions {
    private final boolean disableThinking;

    public RagChatOptions(String baseUrl, boolean disableThinking) {
        boolean deepSeek;
        try { deepSeek = "api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost()); }
        catch (RuntimeException invalid) { deepSeek = false; }
        this.disableThinking = disableThinking && deepSeek;
    }

    public OpenAiChatOptions.Builder options(Integer maxOutputTokens, boolean json) {
        var builder = OpenAiChatOptions.builder().responseFormat(ResponseFormat.builder()
                .type(json ? ResponseFormat.Type.JSON_OBJECT : ResponseFormat.Type.TEXT).build());
        if (maxOutputTokens != null) builder.maxTokens(maxOutputTokens);
        if (disableThinking) builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        return builder;
    }

    public boolean thinkingDisabled() { return disableThinking; }
}
