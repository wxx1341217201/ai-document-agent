package com.wxx.aidocumentagent.ingestion.application;

/** 发布器端口使 outbox 状态机可在不启动 RabbitMQ 的单元测试中验证。 */
public interface IngestionMessagePublisher {

    void publish(OutboxDispatchEnvelope envelope);
}
