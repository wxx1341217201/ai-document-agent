package com.wxx.aidocumentagent.agent;

import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFailureDiagnosticsTest {
    @Test
    void retriesOnlyTransientFailuresAndNeverCopiesProviderErrorText() {
        var unauthorized = ModelFailureDiagnostics.classify(new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "secret-key"));
        assertThat(unauthorized.retryable()).isFalse();
        assertThat(unauthorized.httpStatus()).isEqualTo(401);
        assertThat(unauthorized.toString()).doesNotContain("secret-key");
        assertThat(ModelFailureDiagnostics.classify(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)).retryable()).isTrue();
        assertThat(ModelFailureDiagnostics.classify(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)).retryable()).isTrue();
        assertThat(ModelFailureDiagnostics.classify(new RuntimeException(new ConnectException("secret-endpoint"))).category())
                .isEqualTo("NETWORK_FAILURE");
        assertThat(ModelFailureDiagnostics.classify(new IllegalArgumentException("private-request")).retryable()).isFalse();
        assertThat(ModelFailureDiagnostics.classify(new RuntimeException(new java.io.EOFException("private-response"))).category())
                .isEqualTo("NETWORK_FAILURE");
        assertThat(ModelFailureDiagnostics.classify(new java.net.SocketException("private-endpoint")).retryable()).isTrue();
    }

    @Test
    void understandsTheSdkExceptionsUsedBySpringAiTwo() {
        for (int status : new int[]{400, 401, 402, 429, 503}) {
            var error = org.mockito.Mockito.mock(com.openai.errors.OpenAIServiceException.class);
            org.mockito.Mockito.when(error.statusCode()).thenReturn(status);
            var classified = ModelFailureDiagnostics.classify(error);
            assertThat(classified.httpStatus()).isEqualTo(status);
            assertThat(classified.retryable()).isEqualTo(status == 429 || status == 503);
            org.mockito.Mockito.verify(error, org.mockito.Mockito.never()).body();
        }
        assertThat(ModelFailureDiagnostics.classify(new com.openai.errors.OpenAIIoException("secret-request")).retryable()).isTrue();
        assertThat(ModelFailureDiagnostics.classify(new com.openai.errors.OpenAIRetryableException()).retryable()).isTrue();
    }
}
