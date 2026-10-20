package com.wxx.aidocumentagent.progress;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

/** Each socket has a bounded queue and one serial sender. A slow socket is closed, never backpressuring workers. */
@Component
@ConditionalOnProperty(prefix = "app.progress", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProgressWebSocketBroker {
    private static final Logger log = LoggerFactory.getLogger(ProgressWebSocketBroker.class);
    private static final CloseStatus OVERLOADED = new CloseStatus(1013, "progress consumer too slow");
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Executor sender;
    private final ObjectMapper mapper;
    private final ProgressProperties properties;

    public ProgressWebSocketBroker(@Qualifier("progressSender") Executor sender, ObjectMapper mapper,
                                   ProgressProperties properties) {
        this.sender = sender;
        this.mapper = mapper;
        this.properties = properties;
    }

    public synchronized boolean connect(WebSocketSession session) {
        if (clients.containsKey(session.getId()) || clients.size() >= properties.getMaxSessions()) return false;
        clients.put(session.getId(), new Client(session));
        return true;
    }

    public void disconnect(WebSocketSession session) {
        Client removed = clients.remove(session.getId());
        if (removed != null) removed.dispose();
    }

    public boolean subscribe(WebSocketSession session, ProgressStream stream) {
        Client client = clients.get(session.getId());
        if (client == null) return false;
        if (!client.subscriptions.contains(stream)
                && client.subscriptions.size() >= properties.getMaxSubscriptionsPerSession()) return false;
        client.subscriptions.add(stream);
        return true;
    }

    public void unsubscribe(WebSocketSession session, ProgressStream stream) {
        Client client = clients.get(session.getId());
        if (client != null) client.subscriptions.remove(stream);
    }

    public void sendControl(WebSocketSession session, Object control) {
        Client client = clients.get(session.getId());
        if (client != null) client.enqueue(control);
    }

    public void publish(ProgressStream stream, ProgressEvent event) {
        for (Client client : clients.values()) {
            if (client.subscriptions.contains(stream)) client.enqueue(event);
        }
    }

    private final class Client {
        private final WebSocketSession original;
        private final ConcurrentWebSocketSessionDecorator socket;
        private final Set<ProgressStream> subscriptions = ConcurrentHashMap.newKeySet();
        private final ArrayDeque<String> pending = new ArrayDeque<>();
        private final AtomicBoolean draining = new AtomicBoolean();

        private Client(WebSocketSession session) {
            this.original = session;
            this.socket = new ConcurrentWebSocketSessionDecorator(session, properties.getSendTimeLimitMillis(),
                    properties.getSendBufferSizeBytes());
        }

        private void enqueue(Object value) {
            String json;
            try { json = mapper.writeValueAsString(value); }
            catch (RuntimeException exception) {
                log.warn("进度消息序列化失败", exception);
                return;
            }
            synchronized (pending) {
                if (pending.size() >= properties.getPendingMessagesPerSession()) {
                    close();
                    return;
                }
                pending.addLast(json);
            }
            if (draining.compareAndSet(false, true)) {
                try { sender.execute(this::drain); }
                catch (RuntimeException exception) { close(); }
            }
        }

        private void drain() {
            while (true) {
                String json;
                synchronized (pending) {
                    json = pending.pollFirst();
                    if (json == null) {
                        draining.set(false);
                        return;
                    }
                }
                try {
                    if (!socket.isOpen()) { close(); return; }
                    socket.sendMessage(new TextMessage(json));
                }
                catch (Exception exception) {
                    log.debug("进度连接已断开 sessionId={}", original.getId());
                    close();
                    return;
                }
            }
        }

        private void close() {
            disconnect(original);
            try { if (original.isOpen()) original.close(OVERLOADED); }
            catch (IOException exception) { log.debug("关闭进度连接失败 sessionId={}", original.getId()); }
        }

        private void dispose() {
            subscriptions.clear();
            synchronized (pending) { pending.clear(); }
        }
    }
}
