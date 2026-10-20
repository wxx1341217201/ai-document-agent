package com.wxx.aidocumentagent.agent;

import java.net.ConnectException;
import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.regex.Pattern;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;

/** 只输出分类和状态码，不保留供应商响应、请求、密钥或异常消息。 */
public record ModelFailureDiagnostics(String category, Integer httpStatus, boolean retryable) {
    private static final Pattern STATUS = Pattern.compile("^(?:HTTP\\s*)?([1-5]\\d{2})\\s*[-:]");

    public static ModelFailureDiagnostics emptyResponse() {
        return new ModelFailureDiagnostics("EMPTY_RESPONSE", null, true);
    }

    public static ModelFailureDiagnostics classify(Throwable error) {
        boolean network = false;
        boolean transientAi = false;
        String category = "MODEL_FAILURE";
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof RestClientResponseException response) return http(response.getStatusCode().value());
            if (error instanceof OpenAIServiceException response) return http(response.statusCode());
            String type = error.getClass().getSimpleName();
            if (type.matches("[A-Za-z][A-Za-z0-9_]{0,79}")) category = type;
            if (error.getClass().getPackageName().startsWith("org.springframework.ai.retry")) {
                // Spring AI 部分版本将 HTTP 状态包装到异常中；只提取开头状态码。
                var matcher = STATUS.matcher(error.getMessage() == null ? "" : error.getMessage());
                if (matcher.find()) return http(Integer.parseInt(matcher.group(1)));
                transientAi |= type.equals("TransientAiException");
            }
            network |= error instanceof ResourceAccessException || error instanceof ConnectException
                    || error instanceof SocketTimeoutException || error instanceof HttpTimeoutException
                    || error instanceof OpenAIIoException || error instanceof EOFException || error instanceof SocketException;
            transientAi |= error instanceof OpenAIRetryableException;
        }
        return new ModelFailureDiagnostics(network ? "NETWORK_FAILURE" : category, null, network || transientAi);
    }

    private static ModelFailureDiagnostics http(int status) {
        return new ModelFailureDiagnostics("HTTP_FAILURE", status, status == 429 || status >= 500);
    }
}
