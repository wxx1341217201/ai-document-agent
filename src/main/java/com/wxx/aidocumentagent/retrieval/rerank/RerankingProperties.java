package com.wxx.aidocumentagent.retrieval.rerank;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** Cross-Encoder 的有界请求、认证和熔断配置。默认关闭，确保本地环境不依赖外部模型。 */
@Validated
@ConfigurationProperties(prefix = "app.rerank")
public class RerankingProperties {

    public static final int MAX_CANDIDATES_LIMIT = 100;
    public static final int MAX_TEXT_LENGTH_LIMIT = 16_000;

    private boolean enabled;

    private URI url;

    private String apiKey = "";

    private String apiKeyHeader = "Authorization";

    private String apiKeyPrefix = "Bearer ";

    @Min(1)
    @Max(MAX_CANDIDATES_LIMIT)
    private int maxCandidates = 50;

    @Min(1)
    @Max(MAX_TEXT_LENGTH_LIMIT)
    private int maxTextLength = 4_000;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(5);

    @Min(0)
    @Max(3)
    private int maxRetries = 1;

    @Min(1)
    @Max(10)
    private int circuitFailureThreshold = 3;

    @NotNull
    private Duration circuitOpenDuration = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getUrl() {
        return url;
    }

    public void setUrl(URI url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = apiKeyHeader;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
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

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public Duration getCircuitOpenDuration() {
        return circuitOpenDuration;
    }

    public void setCircuitOpenDuration(Duration circuitOpenDuration) {
        this.circuitOpenDuration = circuitOpenDuration;
    }

    @AssertTrue(message = "app.rerank.enabled=true时必须配置合法的http(s) URL")
    public boolean isUrlValidWhenEnabled() {
        if (!enabled) {
            return true;
        }
        return url != null && url.isAbsolute()
                && ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()));
    }

    @AssertTrue(message = "app.rerank.api-key-header不能为空")
    public boolean isApiKeyHeaderValid() {
        return StringUtils.hasText(apiKeyHeader);
    }

    @AssertTrue(message = "app.rerank的超时与熔断窗口必须大于0")
    public boolean areDurationsPositive() {
        return isPositive(connectTimeout) && isPositive(readTimeout) && isPositive(circuitOpenDuration);
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
