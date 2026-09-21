package com.wxx.aidocumentagent.ingestion.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.wxx.aidocumentagent.ingestion.IngestionProperties;
import com.wxx.aidocumentagent.ingestion.application.IngestionMessagePublisher;
import com.wxx.aidocumentagent.ingestion.application.IngestionPublishException;
import com.wxx.aidocumentagent.ingestion.application.OutboxDispatchEnvelope;
import com.wxx.aidocumentagent.ingestion.domain.OutboxDispatchMode;
import com.wxx.aidocumentagent.ingestion.domain.OutboxMessageType;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 使用 correlated publisher confirm；confirm 未成功前绝不把任务标记为 QUEUED。 */
@Component
public class RabbitIngestionMessagePublisher implements IngestionMessagePublisher {

    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final IngestionMessageCodec messageCodec;
    private final IngestionProperties properties;

    public RabbitIngestionMessagePublisher(ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                                           IngestionMessageCodec messageCodec,
                                           IngestionProperties properties) {
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.messageCodec = messageCodec;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxDispatchEnvelope envelope) {
        RabbitTemplate rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        if (rabbitTemplate == null) {
            throw new IngestionPublishException("RabbitMQ发布器不可用");
        }
        Destination destination = destinationFor(envelope);
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(envelope.eventId());
        if (envelope.dispatchMode() == OutboxDispatchMode.RETRY) {
            messageProperties.setExpiration(Long.toString(envelope.retryDelayMillis()));
        }
        Message message = new Message(messageCodec.encode(envelope.payload()), messageProperties);
        CorrelationData correlationData = new CorrelationData(envelope.eventId());
        try {
            rabbitTemplate.send(destination.exchange(), destination.routingKey(), message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    properties.getPublisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IngestionPublishException("RabbitMQ publisher confirm NACK");
            }
            if (correlationData.getReturned() != null) {
                throw new IngestionPublishException("RabbitMQ消息没有匹配的队列");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IngestionPublishException("等待RabbitMQ publisher confirm时被中断", exception);
        }
        catch (ExecutionException | TimeoutException exception) {
            throw new IngestionPublishException("RabbitMQ publisher confirm未成功", exception);
        }
        catch (org.springframework.amqp.AmqpException exception) {
            throw new IngestionPublishException("RabbitMQ发布失败", exception);
        }
    }

    private Destination destinationFor(OutboxDispatchEnvelope envelope) {
        IngestionProperties.Messaging messaging = properties.getMessaging();
        if (envelope.messageType() == OutboxMessageType.DOCUMENT_COORDINATOR) {
            return envelope.dispatchMode() == OutboxDispatchMode.RETRY
                    ? new Destination(messaging.getDocumentRetryExchange(), messaging.getDocumentRetryRoutingKey())
                    : new Destination(messaging.getDocumentExchange(), messaging.getDocumentRoutingKey());
        }
        return envelope.dispatchMode() == OutboxDispatchMode.RETRY
                ? new Destination(messaging.getBatchRetryExchange(), messaging.getBatchRetryRoutingKey())
                : new Destination(messaging.getBatchExchange(), messaging.getBatchRoutingKey());
    }

    private record Destination(String exchange, String routingKey) {
    }
}
