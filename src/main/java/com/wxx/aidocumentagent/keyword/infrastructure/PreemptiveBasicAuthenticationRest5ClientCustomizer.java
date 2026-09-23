package com.wxx.aidocumentagent.keyword.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.message.BasicHeader;
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer;
import org.springframework.util.StringUtils;

/**
 * 当配置 Basic Auth 时预先发送 Authorization 头。
 *
 * <p>Elasticsearch 9 会同时给出 Basic 与 ApiKey challenge；预先认证可避免底层 HTTP 客户端
 * 在多 challenge 协商时失败。完整 Basic 凭据优先；只有未配置完整 Basic 凭据时才使用 API key 路径。</p>
 */
public final class PreemptiveBasicAuthenticationRest5ClientCustomizer implements Rest5ClientBuilderCustomizer {

    private final String authorizationHeader;

    public PreemptiveBasicAuthenticationRest5ClientCustomizer(String username, String password) {
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            String credentials = username + ":" + password;
            this.authorizationHeader = "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        else {
            this.authorizationHeader = null;
        }
    }

    @Override
    public void customize(Rest5ClientBuilder builder) {
        if (authorizationHeader == null) {
            return;
        }
        // 覆盖 Spring Boot 为“空 API key”设置的空 ApiKey 头。
        builder.setDefaultHeaders(new Header[] { new BasicHeader(HttpHeaders.AUTHORIZATION, authorizationHeader) });
    }

    @Override
    public void customize(HttpAsyncClientBuilder builder) {
        if (authorizationHeader == null) {
            return;
        }
        // 默认头可能被后续客户端配置覆盖；请求发送前再次强制设置，避免触发多 challenge 协商。
        builder.addRequestInterceptorFirst((HttpRequestInterceptor) (request, entity, context) ->
                request.setHeader(HttpHeaders.AUTHORIZATION, authorizationHeader));
    }
}
