package com.wxx.aidocumentagent.rag;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** M11 的上下文、模型调用和超时预算。字符预算是保守的 token 估算，最终不超过模型窗口。 */
@Validated
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    public static final int MAX_TOP_K = 20;

    private boolean enabled = true;

    @Min(1)
    @Max(MAX_TOP_K)
    private int defaultTopK = 8;

    @Min(1_024)
    private int contextWindowTokens = 16_384;

    @Min(128)
    private int reservedOutputTokens = 2_048;

    @Min(512)
    private int maxContextCharacters = 12_000;

    @Min(64)
    private int maxChunkCharacters = 3_000;

    @Min(1)
    private int minChunkCharacters = 200;

    @Min(1)
    private int quoteMaxCharacters = 500;

    @Min(0)
    private int redundantOverlapCharacters = 80;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(3);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(30);

    @NotNull
    private Duration chatTimeout = Duration.ofSeconds(30);

    @NotBlank
    private String model = "gpt-4.1-mini";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    public void setReservedOutputTokens(int reservedOutputTokens) {
        this.reservedOutputTokens = reservedOutputTokens;
    }

    public int getMaxContextCharacters() {
        return maxContextCharacters;
    }

    public void setMaxContextCharacters(int maxContextCharacters) {
        this.maxContextCharacters = maxContextCharacters;
    }

    public int getMaxChunkCharacters() {
        return maxChunkCharacters;
    }

    public void setMaxChunkCharacters(int maxChunkCharacters) {
        this.maxChunkCharacters = maxChunkCharacters;
    }

    public int getMinChunkCharacters() {
        return minChunkCharacters;
    }

    public void setMinChunkCharacters(int minChunkCharacters) {
        this.minChunkCharacters = minChunkCharacters;
    }

    public int getQuoteMaxCharacters() {
        return quoteMaxCharacters;
    }

    public void setQuoteMaxCharacters(int quoteMaxCharacters) {
        this.quoteMaxCharacters = quoteMaxCharacters;
    }

    public int getRedundantOverlapCharacters() {
        return redundantOverlapCharacters;
    }

    public void setRedundantOverlapCharacters(int redundantOverlapCharacters) {
        this.redundantOverlapCharacters = redundantOverlapCharacters;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getChatTimeout() {
        return chatTimeout;
    }

    public void setChatTimeout(Duration chatTimeout) {
        this.chatTimeout = chatTimeout;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int resolveTopK(Integer requestedTopK) {
        return requestedTopK == null ? defaultTopK : requestedTopK;
    }

    /** 模型输入可用 token；固定系统提示词、问题和证据块由 Spring AI TokenCountEstimator 实测扣除。 */
    public int inputTokenBudget() {
        return contextWindowTokens - reservedOutputTokens;
    }

    @AssertTrue(message = "app.rag.reserved-output-tokens必须小于context-window-tokens")
    public boolean isOutputBudgetWithinContextWindow() {
        return reservedOutputTokens < contextWindowTokens;
    }

    @AssertTrue(message = "app.rag.max-chunk-characters必须不小于min-chunk-characters")
    public boolean isChunkBudgetValid() {
        return maxChunkCharacters >= minChunkCharacters;
    }

    @AssertTrue(message = "app.rag.connect-timeout、read-timeout和chat-timeout必须大于0")
    public boolean areTimeoutsPositive() {
        return isPositive(connectTimeout) && isPositive(readTimeout) && isPositive(chatTimeout);
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

}
