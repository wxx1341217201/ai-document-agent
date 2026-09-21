package com.wxx.aidocumentagent.ingestion;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** M06 的拓扑、并发度和有限重试策略都集中在这里。 */
@Validated
@ConfigurationProperties(prefix = "app.ingestion")
public class IngestionProperties {

    private boolean enabled = true;

    @Min(1)
    private int batchSize = 50;

    @Min(1)
    private int listenerConcurrency = 3;

    @Min(1)
    private int listenerMaxConcurrency = 8;

    @Min(1)
    private int prefetch = 20;

    @Min(1)
    private int maxAttempts = 3;

    @NotNull
    private Duration retryInitialDelay = Duration.ofSeconds(5);

    @NotNull
    private Duration retryMaxDelay = Duration.ofMinutes(5);

    @NotNull
    private Duration publisherConfirmTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration staleProcessingTimeout = Duration.ofMinutes(30);

    @Valid
    private Outbox outbox = new Outbox();

    @Valid
    private Messaging messaging = new Messaging();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getListenerConcurrency() {
        return listenerConcurrency;
    }

    public void setListenerConcurrency(int listenerConcurrency) {
        this.listenerConcurrency = listenerConcurrency;
    }

    public int getListenerMaxConcurrency() {
        return listenerMaxConcurrency;
    }

    public void setListenerMaxConcurrency(int listenerMaxConcurrency) {
        this.listenerMaxConcurrency = listenerMaxConcurrency;
    }

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int prefetch) {
        this.prefetch = prefetch;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryInitialDelay() {
        return retryInitialDelay;
    }

    public void setRetryInitialDelay(Duration retryInitialDelay) {
        this.retryInitialDelay = retryInitialDelay;
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
    }

    public Duration getPublisherConfirmTimeout() {
        return publisherConfirmTimeout;
    }

    public void setPublisherConfirmTimeout(Duration publisherConfirmTimeout) {
        this.publisherConfirmTimeout = publisherConfirmTimeout;
    }

    public Duration getStaleProcessingTimeout() {
        return staleProcessingTimeout;
    }

    public void setStaleProcessingTimeout(Duration staleProcessingTimeout) {
        this.staleProcessingTimeout = staleProcessingTimeout;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public Messaging getMessaging() {
        return messaging;
    }

    public void setMessaging(Messaging messaging) {
        this.messaging = messaging;
    }

    @AssertTrue(message = "app.ingestion.listener-max-concurrency不能小于listener-concurrency")
    public boolean isListenerConcurrencyValid() {
        return listenerMaxConcurrency >= listenerConcurrency;
    }

    @AssertTrue(message = "app.ingestion.retry-max-delay不能小于retry-initial-delay")
    public boolean isRetryDelayRangeValid() {
        return !retryMaxDelay.minus(retryInitialDelay).isNegative();
    }

    public long retryDelayMillis(int retryAttempt) {
        if (retryAttempt <= 0) {
            return 0;
        }
        long initial = retryInitialDelay.toMillis();
        long maximum = retryMaxDelay.toMillis();
        long multiplier = 1L << Math.min(retryAttempt - 1, 30);
        if (initial > maximum / multiplier) {
            return maximum;
        }
        return Math.min(maximum, initial * multiplier);
    }

    public static class Outbox {

        @NotNull
        private Duration scanDelay = Duration.ofSeconds(5);

        @Min(1)
        private int batchSize = 100;

        @NotNull
        private Duration dispatchLease = Duration.ofSeconds(30);

        @Min(1)
        private int maxPublishAttempts = 5;

        public Duration getScanDelay() {
            return scanDelay;
        }

        public void setScanDelay(Duration scanDelay) {
            this.scanDelay = scanDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getDispatchLease() {
            return dispatchLease;
        }

        public void setDispatchLease(Duration dispatchLease) {
            this.dispatchLease = dispatchLease;
        }

        public int getMaxPublishAttempts() {
            return maxPublishAttempts;
        }

        public void setMaxPublishAttempts(int maxPublishAttempts) {
            this.maxPublishAttempts = maxPublishAttempts;
        }
    }

    public static class Messaging {

        @NotBlank private String documentExchange = "document.ingestion.exchange";
        @NotBlank private String documentQueue = "document.ingestion.queue";
        @NotBlank private String documentRoutingKey = "document.ingestion.requested";
        @NotBlank private String documentRetryExchange = "document.ingestion.retry.exchange";
        @NotBlank private String documentRetryQueue = "document.ingestion.retry.queue";
        @NotBlank private String documentRetryRoutingKey = "document.ingestion.retry";
        @NotBlank private String documentDeadLetterExchange = "document.ingestion.dlx";
        @NotBlank private String documentDeadLetterQueue = "document.ingestion.dlq";
        @NotBlank private String documentDeadLetterRoutingKey = "document.ingestion.dead";
        @NotBlank private String batchExchange = "document.batch.exchange";
        @NotBlank private String batchQueue = "document.batch.queue";
        @NotBlank private String batchRoutingKey = "document.batch.requested";
        @NotBlank private String batchRetryExchange = "document.batch.retry.exchange";
        @NotBlank private String batchRetryQueue = "document.batch.retry.queue";
        @NotBlank private String batchRetryRoutingKey = "document.batch.retry";
        @NotBlank private String batchDeadLetterExchange = "document.batch.dlx";
        @NotBlank private String batchDeadLetterQueue = "document.batch.dlq";
        @NotBlank private String batchDeadLetterRoutingKey = "document.batch.dead";

        public String getDocumentExchange() { return documentExchange; }
        public void setDocumentExchange(String value) { documentExchange = value; }
        public String getDocumentQueue() { return documentQueue; }
        public void setDocumentQueue(String value) { documentQueue = value; }
        public String getDocumentRoutingKey() { return documentRoutingKey; }
        public void setDocumentRoutingKey(String value) { documentRoutingKey = value; }
        public String getDocumentRetryExchange() { return documentRetryExchange; }
        public void setDocumentRetryExchange(String value) { documentRetryExchange = value; }
        public String getDocumentRetryQueue() { return documentRetryQueue; }
        public void setDocumentRetryQueue(String value) { documentRetryQueue = value; }
        public String getDocumentRetryRoutingKey() { return documentRetryRoutingKey; }
        public void setDocumentRetryRoutingKey(String value) { documentRetryRoutingKey = value; }
        public String getDocumentDeadLetterExchange() { return documentDeadLetterExchange; }
        public void setDocumentDeadLetterExchange(String value) { documentDeadLetterExchange = value; }
        public String getDocumentDeadLetterQueue() { return documentDeadLetterQueue; }
        public void setDocumentDeadLetterQueue(String value) { documentDeadLetterQueue = value; }
        public String getDocumentDeadLetterRoutingKey() { return documentDeadLetterRoutingKey; }
        public void setDocumentDeadLetterRoutingKey(String value) { documentDeadLetterRoutingKey = value; }
        public String getBatchExchange() { return batchExchange; }
        public void setBatchExchange(String value) { batchExchange = value; }
        public String getBatchQueue() { return batchQueue; }
        public void setBatchQueue(String value) { batchQueue = value; }
        public String getBatchRoutingKey() { return batchRoutingKey; }
        public void setBatchRoutingKey(String value) { batchRoutingKey = value; }
        public String getBatchRetryExchange() { return batchRetryExchange; }
        public void setBatchRetryExchange(String value) { batchRetryExchange = value; }
        public String getBatchRetryQueue() { return batchRetryQueue; }
        public void setBatchRetryQueue(String value) { batchRetryQueue = value; }
        public String getBatchRetryRoutingKey() { return batchRetryRoutingKey; }
        public void setBatchRetryRoutingKey(String value) { batchRetryRoutingKey = value; }
        public String getBatchDeadLetterExchange() { return batchDeadLetterExchange; }
        public void setBatchDeadLetterExchange(String value) { batchDeadLetterExchange = value; }
        public String getBatchDeadLetterQueue() { return batchDeadLetterQueue; }
        public void setBatchDeadLetterQueue(String value) { batchDeadLetterQueue = value; }
        public String getBatchDeadLetterRoutingKey() { return batchDeadLetterRoutingKey; }
        public void setBatchDeadLetterRoutingKey(String value) { batchDeadLetterRoutingKey = value; }
    }
}
