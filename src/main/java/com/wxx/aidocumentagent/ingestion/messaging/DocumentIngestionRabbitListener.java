package com.wxx.aidocumentagent.ingestion.messaging;

import com.wxx.aidocumentagent.ingestion.application.DocumentIngestionCoordinator;
import com.wxx.aidocumentagent.ingestion.application.IngestionFailureDisposition;
import com.wxx.aidocumentagent.ingestion.application.IngestionFailureHandler;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 文档协调消费者在业务事务完成后才正常返回，由容器执行 ACK。 */
@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestionRabbitListener {

    private final IngestionMessageCodec messageCodec;
    private final DocumentIngestionCoordinator coordinator;
    private final IngestionFailureHandler failureHandler;

    public DocumentIngestionRabbitListener(IngestionMessageCodec messageCodec,
                                           DocumentIngestionCoordinator coordinator,
                                           IngestionFailureHandler failureHandler) {
        this.messageCodec = messageCodec;
        this.coordinator = coordinator;
        this.failureHandler = failureHandler;
    }

    @RabbitListener(queues = "${app.ingestion.messaging.document-queue}",
            containerFactory = "ingestionRabbitListenerContainerFactory")
    public void consume(Message rawMessage) {
        DocumentIngestionMessage message = messageCodec.decodeDocument(rawMessage);
        try {
            coordinator.process(message);
        }
        catch (IngestionMessageValidationException exception) {
            throw reject(exception);
        }
        catch (RuntimeException exception) {
            try {
                IngestionFailureDisposition disposition = failureHandler.handleDocumentFailure(message, exception);
                if (disposition == IngestionFailureDisposition.RETRY_SCHEDULED
                        || disposition == IngestionFailureDisposition.IGNORE) {
                    return;
                }
            }
            catch (IngestionMessageValidationException validationException) {
                throw reject(validationException);
            }
            throw reject(exception);
        }
    }

    private AmqpRejectAndDontRequeueException reject(RuntimeException exception) {
        return new AmqpRejectAndDontRequeueException("文档摄取消息进入死信队列", exception);
    }
}
