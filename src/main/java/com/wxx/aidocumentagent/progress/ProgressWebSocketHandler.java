package com.wxx.aidocumentagent.progress;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

/** JSON protocol adapter. Authorization and resource scoping live in ProgressSubscriptionService. */
@Component
@ConditionalOnProperty(prefix = "app.progress", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProgressWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ProgressWebSocketHandler.class);
    private final ProgressSubscriptionService subscriptions;
    private final ProgressSequenceStore sequences;
    private final ProgressWebSocketBroker broker;
    private final ObjectMapper mapper;

    public ProgressWebSocketHandler(ProgressSubscriptionService subscriptions, ProgressSequenceStore sequences,
                                    ProgressWebSocketBroker broker, ObjectMapper mapper) {
        this.subscriptions = subscriptions;
        this.sequences = sequences;
        this.broker = broker;
        this.mapper = mapper;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!broker.connect(session)) {
            session.close(new CloseStatus(1013, "progress capacity reached"));
            return;
        }
        broker.sendControl(session, Map.of("type", "CONNECTED"));
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayloadLength() > 4096) { reject(session, "BAD_REQUEST"); return; }
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            if (root == null || !root.isObject()) { reject(session, "BAD_REQUEST"); return; }
            String action = string(root, "action");
            if (!"SUBSCRIBE".equals(action) && !"UNSUBSCRIBE".equals(action)) {
                reject(session, "BAD_REQUEST");
                return;
            }
            String resourceName = string(root, "resource");
            if (resourceName == null) { reject(session, "BAD_REQUEST"); return; }
            ProgressResource resource = ProgressResource.valueOf(resourceName);
            long knowledgeBaseId = number(root, "knowledgeBaseId");
            ProgressSubscriptionRequest request = new ProgressSubscriptionRequest(resource, knowledgeBaseId,
                    string(root, "executionId"), optionalNumber(root, "documentId"));
            ProgressSubscriber subscriber = new ProgressSubscriber(session.getPrincipal(),
                    Boolean.TRUE.equals(session.getAttributes().get(ProgressHandshakeInterceptor.AUTHENTICATED)));
            ProgressStream stream = subscriptions.resolve(subscriber, request);
            if ("SUBSCRIBE".equals(action)) {
                if (!broker.subscribe(session, stream)) { reject(session, "SUBSCRIPTION_LIMIT"); return; }
                long latestSequence;
                try { latestSequence = sequences.current(stream); }
                catch (RuntimeException exception) {
                    broker.unsubscribe(session, stream);
                    throw exception;
                }
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("type", "SUBSCRIBED");
                response.put("resource", stream.resource());
                response.put("executionId", stream.executionId());
                response.put("knowledgeBaseId", stream.knowledgeBaseId());
                response.put("documentId", stream.documentId());
                response.put("latestSequence", latestSequence);
                response.put("statusUrl", statusUrl(stream));
                broker.sendControl(session, response);
            }
            else if ("UNSUBSCRIBE".equals(action)) {
                broker.unsubscribe(session, stream);
                broker.sendControl(session, Map.of("type", "UNSUBSCRIBED"));
            }
            else reject(session, "BAD_REQUEST");
        }
        catch (ProgressSubscriptionException exception) { reject(session, exception.code()); }
        catch (IllegalArgumentException | JacksonException exception) { reject(session, "BAD_REQUEST"); }
        catch (RuntimeException exception) {
            log.warn("进度订阅处理失败 sessionId={}", session.getId(), exception);
            reject(session, "UNAVAILABLE");
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broker.disconnect(session);
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        broker.disconnect(session);
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private String statusUrl(ProgressStream stream) {
        if (stream.resource() == ProgressResource.EXECUTION) {
            return "/api/v1/agent/executions/" + stream.executionId()
                    + "?knowledgeBaseId=" + stream.knowledgeBaseId();
        }
        return "/api/v1/knowledge-bases/" + stream.knowledgeBaseId() + "/documents/" + stream.documentId();
    }

    private void reject(WebSocketSession session, String code) {
        broker.sendControl(session, Map.of("type", "SUBSCRIPTION_REJECTED", "code", code));
    }

    private String string(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("Expected text");
        return node.asText();
    }

    private long number(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber()) throw new IllegalArgumentException("Expected integer");
        return node.longValue();
    }

    private Long optionalNumber(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber()) throw new IllegalArgumentException("Expected integer");
        return node.longValue();
    }
}
