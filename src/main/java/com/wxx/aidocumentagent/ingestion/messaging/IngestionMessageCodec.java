package com.wxx.aidocumentagent.ingestion.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 明确 JSON 编解码，避免把 Java 对象序列化或文件正文传入 RabbitMQ。 */
@Component
public class IngestionMessageCodec {

    private final ObjectMapper objectMapper;

    public IngestionMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DocumentIngestionMessage decodeDocument(Message message) {
        DocumentIngestionMessage decoded = decode(message, DocumentIngestionMessage.class);
        decoded.validate();
        return decoded;
    }

    public ChunkBatchMessage decodeBatch(Message message) {
        ChunkBatchMessage decoded = decode(message, ChunkBatchMessage.class);
        decoded.validate();
        return decoded;
    }

    public byte[] encode(Object message) {
        try {
            return objectMapper.writeValueAsBytes(message);
        }
        catch (JacksonException exception) {
            throw new IngestionMessageValidationException("摄取消息序列化失败", exception);
        }
    }

    private <T> T decode(Message message, Class<T> type) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            throw new IngestionMessageValidationException("摄取消息为空");
        }
        try {
            return objectMapper.readValue(message.getBody(), type);
        }
        catch (JacksonException exception) {
            throw new IngestionMessageValidationException("摄取消息JSON无法解析", exception);
        }
    }
}
