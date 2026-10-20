package com.wxx.aidocumentagent.conversation;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Redis 短期上下文的可配置上限；数据库审计记录不会因缓存裁剪而删除。 */
@Validated
@ConfigurationProperties(prefix = "app.conversation")
public class ConversationMemoryProperties {

    private boolean enabled = true;

    @NotNull
    private Duration memoryTtl = Duration.ofHours(24);

    @Min(1)
    private int maxMemoryMessages = 20;

    @Min(1)
    private int maxMemoryTokens = 4_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getMemoryTtl() {
        return memoryTtl;
    }

    public void setMemoryTtl(Duration memoryTtl) {
        this.memoryTtl = memoryTtl;
    }

    public int getMaxMemoryMessages() {
        return maxMemoryMessages;
    }

    public void setMaxMemoryMessages(int maxMemoryMessages) {
        this.maxMemoryMessages = maxMemoryMessages;
    }

    public int getMaxMemoryTokens() {
        return maxMemoryTokens;
    }

    public void setMaxMemoryTokens(int maxMemoryTokens) {
        this.maxMemoryTokens = maxMemoryTokens;
    }

    @AssertTrue(message = "app.conversation.memory-ttl必须大于0")
    public boolean isMemoryTtlPositive() {
        return memoryTtl != null && !memoryTtl.isZero() && !memoryTtl.isNegative();
    }
}
