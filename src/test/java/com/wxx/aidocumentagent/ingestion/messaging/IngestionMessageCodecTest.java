package com.wxx.aidocumentagent.ingestion.messaging;

import java.time.Instant;
import java.util.UUID;

import com.wxx.aidocumentagent.ingestion.domain.IngestionOperation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionMessageCodecTest {

    private final IngestionMessageCodec codec = new IngestionMessageCodec(new JsonMapper());

    @Test
    void 文档协调消息序列化后仍保持版本和标识字段() {
        DocumentIngestionMessage message = new DocumentIngestionMessage(UUID.randomUUID(), UUID.randomUUID(), 101L, 9L,
                IngestionOperation.PARSE_AND_SPLIT, 0, Instant.parse("2026-09-20T17:30:00Z"), 1);

        DocumentIngestionMessage decoded = codec.decodeDocument(new Message(codec.encode(message), new MessageProperties()));

        assertThat(decoded).isEqualTo(message);
    }

    @Test
    void 不支持的schemaVersion会被拒绝进入业务处理() {
        String invalid = "{\"eventId\":\"550e8400-e29b-41d4-a716-446655440000\",\"jobId\":\"550e8400-e29b-41d4-a716-446655440001\",\"documentId\":101,\"knowledgeBaseId\":9,\"operation\":\"PARSE_AND_SPLIT\",\"attempt\":0,\"occurredAt\":\"2026-09-20T17:30:00Z\",\"schemaVersion\":2}";

        assertThatThrownBy(() -> codec.decodeDocument(new Message(invalid.getBytes(), new MessageProperties())))
                .isInstanceOf(IngestionMessageValidationException.class);
    }
}
