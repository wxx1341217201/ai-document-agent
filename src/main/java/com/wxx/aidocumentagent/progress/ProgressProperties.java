package com.wxx.aidocumentagent.progress;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.progress")
public class ProgressProperties {
    private String subscriptionToken = "";
    private int maxSessions = 128;
    private int maxSubscriptionsPerSession = 8;
    private int pendingMessagesPerSession = 32;
    private int senderThreads = 4;
    private int senderQueueCapacity = 128;
    private int sendTimeLimitMillis = 5000;
    private int sendBufferSizeBytes = 65536;

    public String getSubscriptionToken() { return subscriptionToken; }
    public void setSubscriptionToken(String subscriptionToken) { this.subscriptionToken = subscriptionToken; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
    public int getMaxSubscriptionsPerSession() { return maxSubscriptionsPerSession; }
    public void setMaxSubscriptionsPerSession(int value) { this.maxSubscriptionsPerSession = value; }
    public int getPendingMessagesPerSession() { return pendingMessagesPerSession; }
    public void setPendingMessagesPerSession(int value) { this.pendingMessagesPerSession = value; }
    public int getSenderThreads() { return senderThreads; }
    public void setSenderThreads(int senderThreads) { this.senderThreads = senderThreads; }
    public int getSenderQueueCapacity() { return senderQueueCapacity; }
    public void setSenderQueueCapacity(int value) { this.senderQueueCapacity = value; }
    public int getSendTimeLimitMillis() { return sendTimeLimitMillis; }
    public void setSendTimeLimitMillis(int value) { this.sendTimeLimitMillis = value; }
    public int getSendBufferSizeBytes() { return sendBufferSizeBytes; }
    public void setSendBufferSizeBytes(int value) { this.sendBufferSizeBytes = value; }
}
