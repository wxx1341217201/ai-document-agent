package com.wxx.aidocumentagent.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionRabbitTopologyTest {

    @Test
    void 主队列重试队列和死信队列均为持久化并正确回流() {
        IngestionProperties properties = new IngestionProperties();
        IngestionConfiguration.RabbitTopologyConfiguration topology =
                new IngestionConfiguration.RabbitTopologyConfiguration();

        Queue documentMain = topology.documentIngestionQueue(properties);
        Queue documentRetry = topology.documentIngestionRetryQueue(properties);
        Queue batchMain = topology.documentBatchQueue(properties);
        Queue batchRetry = topology.documentBatchRetryQueue(properties);

        assertThat(documentMain.isDurable()).isTrue();
        assertThat(documentRetry.isDurable()).isTrue();
        assertThat(batchMain.isDurable()).isTrue();
        assertThat(batchRetry.isDurable()).isTrue();
        assertThat(documentRetry.getArguments()).containsEntry("x-dead-letter-exchange",
                properties.getMessaging().getDocumentExchange());
        assertThat(batchRetry.getArguments()).containsEntry("x-dead-letter-exchange",
                properties.getMessaging().getBatchExchange());
    }
}
