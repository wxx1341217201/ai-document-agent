package com.wxx.aidocumentagent.progress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** The shared token is deployment access until a user identity integration replaces the authorizer. */
public class ProgressHandshakeInterceptor implements HandshakeInterceptor {
    public static final String AUTHENTICATED = "progressSharedTokenAuthenticated";
    private final ProgressProperties properties;

    public ProgressHandshakeInterceptor(ProgressProperties properties) { this.properties = properties; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String configured = properties.getSubscriptionToken();
        String provided = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean valid = configured != null && !configured.isBlank() && provided != null
                && provided.startsWith("Bearer ")
                && MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                    provided.substring(7).getBytes(StandardCharsets.UTF_8));
        attributes.put(AUTHENTICATED, valid);
        return true;
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler handler, Exception exception) { }
}
