package com.wxx.aidocumentagent.agent.execution;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agent.execution")
public class ExecutionProperties {
    @NotNull private Duration recoveryWindow = Duration.ofHours(24);
    @NotNull private Duration lease = Duration.ofMinutes(2);
    private int workerThreads = 2;
    private int queueCapacity = 100;

    public Duration getRecoveryWindow() { return recoveryWindow; }
    public void setRecoveryWindow(Duration value) { recoveryWindow = value; }
    public Duration getLease() { return lease; }
    public void setLease(Duration value) { lease = value; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int value) { workerThreads = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }

    @AssertTrue(message = "执行恢复窗口、租约和线程池参数必须为正数")
    public boolean isValid() {
        return recoveryWindow != null && !recoveryWindow.isZero() && !recoveryWindow.isNegative()
                && lease != null && !lease.isZero() && !lease.isNegative()
                && workerThreads > 0 && queueCapacity > 0;
    }
}
