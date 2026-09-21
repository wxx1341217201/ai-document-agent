package com.wxx.aidocumentagent.ingestion.messaging;

import com.wxx.aidocumentagent.ingestion.application.ChunkBatchIndexingApplicationService;
import com.wxx.aidocumentagent.ingestion.application.IngestionFailureDisposition;
import com.wxx.aidocumentagent.ingestion.application.IngestionFailureHandler;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** batch worker 可由 RabbitMQ consumer pool 并行运行；重复 batchId 由数据库状态机忽略。 */
@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChunkBatchRabbitListener {

    private final IngestionMessageCodec messageCodec;
    private final ChunkBatchIndexingApplicationService indexingApplicationService;
    private final IngestionFailureHandler failureHandler;

    public ChunkBatchRabbitListener(IngestionMessageCodec messageCodec,
                                    ChunkBatchIndexingApplicationService indexingApplicationService,
                                    IngestionFailureHandler failureHandler) {
        this.messageCodec = messageCodec;
        this.indexingApplicationService = indexingApplicationService;
        this.failureHandler = failureHandler;
    }

    @RabbitListener(queues = "${app.ingestion.messaging.batch-queue}",
            containerFactory = "ingestionRabbitListenerContainerFactory")
    public void consume(Message rawMessage) {
        ChunkBatchMessage message = messageCodec.decodeBatch(rawMessage);
        try {
            indexingApplicationService.process(message);
        }
        catch (IngestionMessageValidationException exception) {
            throw reject(exception);
        }
        catch (RuntimeException exception) {
            try {
                IngestionFailureDisposition disposition = failureHandler.handleBatchFailure(message, exception);
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
        return new AmqpRejectAndDontRequeueException("batch摄取消息进入死信队列", exception);
    }
}
