package com.wxx.aidocumentagent.agent;

/** 可适配 Spring 事件、WebSocket 或审计消费者的统一发布端口。 */
@FunctionalInterface
public interface AgentEventPublisher {

    void publish(AgentEvent event);
}
