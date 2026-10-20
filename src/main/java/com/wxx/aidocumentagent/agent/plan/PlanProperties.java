package com.wxx.aidocumentagent.agent.plan;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agent.plan")
public class PlanProperties {
    @Min(2) @Max(20) private int maxSteps = 8;
    @Min(0) @Max(2) private int maxRevisions = 1;
    @Min(1) @Max(50) private int maxToolCalls = 12;
    @Min(1) @Max(50) private int maxModelCalls = 12;
    @Min(0) @Max(1) private int maxTransientRetries = 1;
    @Min(1) @Max(50) private int maxEvidenceChunks = 20;
    @Min(256) @Max(8_192) private int planningOutputTokens = 1_024;
    @Min(256) @Max(8_192) private int synthesisOutputTokens = 2_048;
    private boolean jsonPlanning = true;
    private boolean verifyEvidence = false;
    private boolean structuredAnswers;
    @Min(1) @Max(200) private int maxRegisteredSources = 80;

    public boolean isStructuredAnswers() { return structuredAnswers; }
    public void setStructuredAnswers(boolean value) { structuredAnswers = value; }
    public int getMaxRegisteredSources() { return maxRegisteredSources; }
    public void setMaxRegisteredSources(int value) { maxRegisteredSources = value; }
    @AssertTrue(message = "来源登记上限不能小于当前证据窗口上限")
    public boolean areSourceLimitsCoherent() { return !structuredAnswers || maxRegisteredSources >= maxEvidenceChunks; }
    @Min(256) @Max(65_536) private int maxEvidenceTokens = 8_000;
    @NotNull private Duration deadline = Duration.ofSeconds(40);
    @NotNull private Duration modelTimeout = Duration.ofSeconds(20);
    @NotNull private Duration toolTimeout = Duration.ofSeconds(5);

    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int value) { maxSteps = value; }
    public int getMaxRevisions() { return maxRevisions; }
    public void setMaxRevisions(int value) { maxRevisions = value; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int value) { maxToolCalls = value; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int value) { maxModelCalls = value; }
    public int getMaxTransientRetries() { return maxTransientRetries; }
    public void setMaxTransientRetries(int value) { maxTransientRetries = value; }
    public int getMaxEvidenceChunks() { return maxEvidenceChunks; }
    public void setMaxEvidenceChunks(int value) { maxEvidenceChunks = value; }
    public int getPlanningOutputTokens() { return planningOutputTokens; }
    public void setPlanningOutputTokens(int value) { planningOutputTokens = value; }
    public int getSynthesisOutputTokens() { return synthesisOutputTokens; }
    public void setSynthesisOutputTokens(int value) { synthesisOutputTokens = value; }
    public boolean isJsonPlanning() { return jsonPlanning; }
    public void setJsonPlanning(boolean value) { jsonPlanning = value; }
    public boolean isVerifyEvidence() { return verifyEvidence; }
    public void setVerifyEvidence(boolean value) { verifyEvidence = value; }
    public int getMaxEvidenceTokens() { return maxEvidenceTokens; }
    public void setMaxEvidenceTokens(int value) { maxEvidenceTokens = value; }
    public Duration getDeadline() { return deadline; }
    public void setDeadline(Duration value) { deadline = value; }
    public Duration getModelTimeout() { return modelTimeout; }
    public void setModelTimeout(Duration value) { modelTimeout = value; }
    public Duration getToolTimeout() { return toolTimeout; }
    public void setToolTimeout(Duration value) { toolTimeout = value; }

    @AssertTrue(message = "计划模式超时必须大于0")
    public boolean areTimeoutsPositive() {
        return positive(deadline) && positive(modelTimeout) && positive(toolTimeout);
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
