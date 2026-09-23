package com.wxx.aidocumentagent.retrieval;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** M09 的候选数量、超时预算和 RRF 权重均从部署配置读取。 */
@Validated
@ConfigurationProperties(prefix = "app.retrieval")
public class RetrievalProperties {

    private boolean enabled = true;

    @Min(1)
    private int vectorTopK = 20;

    @Min(1)
    private int keywordTopK = 20;

    @Min(1)
    private int finalTopK = 10;

    @Min(1)
    private int rrfK = 60;

    @DecimalMin(value = "0.0", inclusive = true)
    private double vectorWeight = 1D;

    @DecimalMin(value = "0.0", inclusive = true)
    private double keywordWeight = 1D;

    @DecimalMin("-1.0")
    @DecimalMax("1.0")
    private double vectorSimilarityThreshold = 0D;

    @NotNull
    private Duration vectorTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration keywordTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration totalTimeout = Duration.ofSeconds(3);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVectorTopK() {
        return vectorTopK;
    }

    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    public int getKeywordTopK() {
        return keywordTopK;
    }

    public void setKeywordTopK(int keywordTopK) {
        this.keywordTopK = keywordTopK;
    }

    public int getFinalTopK() {
        return finalTopK;
    }

    public void setFinalTopK(int finalTopK) {
        this.finalTopK = finalTopK;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(double vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public double getKeywordWeight() {
        return keywordWeight;
    }

    public void setKeywordWeight(double keywordWeight) {
        this.keywordWeight = keywordWeight;
    }

    public double getVectorSimilarityThreshold() {
        return vectorSimilarityThreshold;
    }

    public void setVectorSimilarityThreshold(double vectorSimilarityThreshold) {
        this.vectorSimilarityThreshold = vectorSimilarityThreshold;
    }

    public Duration getVectorTimeout() {
        return vectorTimeout;
    }

    public void setVectorTimeout(Duration vectorTimeout) {
        this.vectorTimeout = vectorTimeout;
    }

    public Duration getKeywordTimeout() {
        return keywordTimeout;
    }

    public void setKeywordTimeout(Duration keywordTimeout) {
        this.keywordTimeout = keywordTimeout;
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public void setTotalTimeout(Duration totalTimeout) {
        this.totalTimeout = totalTimeout;
    }

    public RetrievalQuery defaultQuery(long knowledgeBaseId, String query) {
        return new RetrievalQuery(knowledgeBaseId, query, vectorTopK, keywordTopK, finalTopK);
    }

    @AssertTrue(message = "app.retrieval.vector-top-k必须在1到100之间")
    public boolean isVectorTopKValid() {
        return isTopKValid(vectorTopK);
    }

    @AssertTrue(message = "app.retrieval.keyword-top-k必须在1到100之间")
    public boolean isKeywordTopKValid() {
        return isTopKValid(keywordTopK);
    }

    @AssertTrue(message = "app.retrieval.final-top-k必须在1到100之间")
    public boolean isFinalTopKValid() {
        return isTopKValid(finalTopK);
    }

    @AssertTrue(message = "app.retrieval检索通道权重不能同时为0")
    public boolean isAtLeastOneWeightPositive() {
        return Double.isFinite(vectorWeight) && Double.isFinite(keywordWeight)
                && (vectorWeight > 0D || keywordWeight > 0D);
    }

    @AssertTrue(message = "app.retrieval.vector-similarity-threshold必须是有限值")
    public boolean isVectorSimilarityThresholdFinite() {
        return Double.isFinite(vectorSimilarityThreshold);
    }

    @AssertTrue(message = "app.retrieval.vector-timeout、keyword-timeout和total-timeout必须大于0")
    public boolean areTimeoutsPositive() {
        return isPositive(vectorTimeout) && isPositive(keywordTimeout) && isPositive(totalTimeout);
    }

    private boolean isTopKValid(int value) {
        return value >= 1 && value <= RetrievalQuery.MAX_TOP_K;
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
