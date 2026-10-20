package com.wxx.aidocumentagent.agent.react;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agent.react")
public class ReActProperties {

    @Min(1) @Max(20)
    private int maxSteps = 6;
    @Min(1) @Max(50)
    private int maxToolCalls = 6;
    @Min(64) @Max(8_000)
    private int maxChunkCharacters = 4_500;
    @Min(128) @Max(8_192)
    private int maxOutputTokens = 1_536;
    private boolean rerank = true;
    private boolean requireInitialSearch = true;
    private boolean structuredAnswers;
    @Min(1) @Max(200) private int maxRegisteredSources = 80;

    public boolean isStructuredAnswers() { return structuredAnswers; }
    public void setStructuredAnswers(boolean value) { structuredAnswers = value; }
    public int getMaxRegisteredSources() { return maxRegisteredSources; }
    public void setMaxRegisteredSources(int value) { maxRegisteredSources = value; }
    @AssertTrue(message = "来源登记上限不能小于当前证据窗口上限")
    public boolean areSourceLimitsCoherent() { return !structuredAnswers || maxRegisteredSources >= maxEvidenceChunks; }
    @Min(256) @Max(65_536)
    private int maxEvidenceTokens = 8_000;
    @Min(2) @Max(6)
    private int repeatThreshold = 2;
    @Min(1) @Max(20)
    private int topK = 6;
    @Min(1) @Max(50)
    private int maxEvidenceChunks = 20;
    @NotNull
    private Duration deadline = Duration.ofSeconds(40);
    @NotNull
    private Duration modelTimeout = Duration.ofSeconds(20);
    @NotNull
    private Duration toolTimeout = Duration.ofSeconds(5);

    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int value) { maxToolCalls = value; }
    public int getMaxChunkCharacters() { return maxChunkCharacters; }
    public void setMaxChunkCharacters(int value) { maxChunkCharacters = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public boolean isRerank() { return rerank; }
    public void setRerank(boolean value) { rerank = value; }
    public boolean isRequireInitialSearch() { return requireInitialSearch; }
    public void setRequireInitialSearch(boolean value) { requireInitialSearch = value; }
    public int getMaxEvidenceTokens() { return maxEvidenceTokens; }
    public void setMaxEvidenceTokens(int value) { maxEvidenceTokens = value; }
    public int getRepeatThreshold() { return repeatThreshold; }
    public void setRepeatThreshold(int repeatThreshold) { this.repeatThreshold = repeatThreshold; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getMaxEvidenceChunks() { return maxEvidenceChunks; }
    public void setMaxEvidenceChunks(int maxEvidenceChunks) { this.maxEvidenceChunks = maxEvidenceChunks; }
    public Duration getDeadline() { return deadline; }
    public void setDeadline(Duration deadline) { this.deadline = deadline; }
    public Duration getModelTimeout() { return modelTimeout; }
    public void setModelTimeout(Duration modelTimeout) { this.modelTimeout = modelTimeout; }
    public Duration getToolTimeout() { return toolTimeout; }
    public void setToolTimeout(Duration toolTimeout) { this.toolTimeout = toolTimeout; }

    @AssertTrue(message = "app.agent.react超时必须大于0")
    public boolean areTimeoutsPositive() {
        return positive(deadline) && positive(modelTimeout) && positive(toolTimeout);
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
