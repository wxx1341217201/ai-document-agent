package com.wxx.aidocumentagent.keyword.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PreemptiveBasicAuthenticationRest5ClientCustomizerTest {

    @Test
    void Basic认证会作为默认请求头设置() {
        Rest5ClientBuilder builder = mock(Rest5ClientBuilder.class);
        PreemptiveBasicAuthenticationRest5ClientCustomizer customizer =
                new PreemptiveBasicAuthenticationRest5ClientCustomizer("elastic", "test-password");

        customizer.customize(builder);

        ArgumentCaptor<Header[]> headers = ArgumentCaptor.forClass(Header[].class);
        verify(builder).setDefaultHeaders(headers.capture());
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:test-password".getBytes(StandardCharsets.UTF_8));
        assertThat(headers.getValue()).singleElement()
                .satisfies(header -> {
                    assertThat(header.getName()).isEqualTo(HttpHeaders.AUTHORIZATION);
                    assertThat(header.getValue()).isEqualTo(expected);
                });
    }

    @Test
    void Basic认证会在请求发送前覆盖已有认证头() throws Exception {
        HttpAsyncClientBuilder builder = mock(HttpAsyncClientBuilder.class);
        PreemptiveBasicAuthenticationRest5ClientCustomizer customizer =
                new PreemptiveBasicAuthenticationRest5ClientCustomizer("elastic", "test-password");

        customizer.customize(builder);

        ArgumentCaptor<HttpRequestInterceptor> interceptor = ArgumentCaptor.forClass(HttpRequestInterceptor.class);
        verify(builder).addRequestInterceptorFirst(interceptor.capture());
        BasicClassicHttpRequest request = new BasicClassicHttpRequest("GET", "/");
        request.setHeader(HttpHeaders.AUTHORIZATION, "ApiKey stale-key");
        interceptor.getValue().process(request, null, null);
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:test-password".getBytes(StandardCharsets.UTF_8));
        assertThat(request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue()).isEqualTo(expected);
    }

    @Test
    void 未配置完整Basic认证时不设置默认认证头() {
        Rest5ClientBuilder missingPasswordBuilder = mock(Rest5ClientBuilder.class);
        new PreemptiveBasicAuthenticationRest5ClientCustomizer("elastic", "").customize(missingPasswordBuilder);
        verifyNoInteractions(missingPasswordBuilder);

        Rest5ClientBuilder apiKeyBuilder = mock(Rest5ClientBuilder.class);
        new PreemptiveBasicAuthenticationRest5ClientCustomizer("", "").customize(apiKeyBuilder);
        verifyNoInteractions(apiKeyBuilder);

        HttpAsyncClientBuilder asyncBuilder = mock(HttpAsyncClientBuilder.class);
        new PreemptiveBasicAuthenticationRest5ClientCustomizer("", "").customize(asyncBuilder);
        verifyNoInteractions(asyncBuilder);
    }
}
