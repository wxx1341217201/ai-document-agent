package com.wxx.aidocumentagent.agent;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 检索优先执行的有界预算及证据门槛。RRF 分数仅用于保守门控，不当作概率。 */
@Validated
@ConfigurationProperties(prefix = "app.agent.retrieval-first")
public class RetrievalFirstProperties {

    @NotNull
    private Duration deadline = Duration.ofSeconds(40);

    private boolean rewriteEnabled = true;
    private boolean structuredAnswers;
    @Min(1) @Max(2) private int maxModelCalls = 2;

    public boolean isStructuredAnswers() { return structuredAnswers; }
    public void setStructuredAnswers(boolean value) { structuredAnswers = value; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int value) { maxModelCalls = value; }
    private boolean rerank = true;

    @Min(1)
    @Max(20)
    private int topK = 8;

    @Min(1)
    private int minEvidenceChunks = 1;

    @DecimalMin("0.0")
    private double minFusedScore = 0.015;

    @Min(1)
    private int maxMemoryMessages = 20;

    @Min(1)
    private int maxMemoryCharacters = 12_000;

    public Duration getDeadline() { return deadline; }
    public void setDeadline(Duration deadline) { this.deadline = deadline; }
    public boolean isRewriteEnabled() { return rewriteEnabled; }
    public void setRewriteEnabled(boolean rewriteEnabled) { this.rewriteEnabled = rewriteEnabled; }
    public boolean isRerank() { return rerank; }
    public void setRerank(boolean rerank) { this.rerank = rerank; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getMinEvidenceChunks() { return minEvidenceChunks; }
    public void setMinEvidenceChunks(int minEvidenceChunks) { this.minEvidenceChunks = minEvidenceChunks; }
    public double getMinFusedScore() { return minFusedScore; }
    public void setMinFusedScore(double minFusedScore) { this.minFusedScore = minFusedScore; }
    public int getMaxMemoryMessages() { return maxMemoryMessages; }
    public void setMaxMemoryMessages(int maxMemoryMessages) { this.maxMemoryMessages = maxMemoryMessages; }
    public int getMaxMemoryCharacters() { return maxMemoryCharacters; }
    public void setMaxMemoryCharacters(int maxMemoryCharacters) { this.maxMemoryCharacters = maxMemoryCharacters; }

    @AssertTrue(message = "app.agent.retrieval-first.deadline必须大于0")
    public boolean isDeadlinePositive() {
        return deadline != null && !deadline.isZero() && !deadline.isNegative();
    }

    @AssertTrue(message = "app.agent.retrieval-first.min-fused-score必须是有限数")
    public boolean isFusedScoreFinite() {
        return Double.isFinite(minFusedScore);
    }
}
