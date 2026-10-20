package com.wxx.aidocumentagent.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IngestionRabbitTopologyTest {

    @Test
    void 继承Boot启动和观测配置但保留Outbox确认与重试策略() {
        var rabbit = new RabbitProperties();
        rabbit.getListener().getSimple().setAutoStartup(false);
        rabbit.getListener().getSimple().setObservationEnabled(true);
        rabbit.getListener().getSimple().getRetry().setEnabled(true);
        rabbit.getListener().getSimple().setAcknowledgeMode(AcknowledgeMode.MANUAL);
        rabbit.getListener().getSimple().setPrefetch(99);
        var properties = new IngestionProperties();
        properties.setPrefetch(7);
        var factory = new IngestionConfiguration.RabbitTopologyConfiguration()
                .ingestionRabbitListenerContainerFactory(mock(ConnectionFactory.class), properties,
                        new SimpleRabbitListenerContainerFactoryConfigurer(rabbit));
        var endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("configuration-test");
        endpoint.setQueueNames("test-queue");
        endpoint.setMessageListener(message -> { });
        var container = factory.createListenerContainer(endpoint);

        assertThat(container.isAutoStartup()).isFalse();
        assertThat(ReflectionTestUtils.getField(container, "observationEnabled")).isEqualTo(true);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
        assertThat((Integer) ReflectionTestUtils.invokeMethod(container, "getPrefetchCount")).isEqualTo(7);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(container, "isDefaultRequeueRejected")).isFalse();
        assertThat((Object[]) ReflectionTestUtils.invokeMethod(container, "getAdviceChain")).isEmpty();
    }

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
