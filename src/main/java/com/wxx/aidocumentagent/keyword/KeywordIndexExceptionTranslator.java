package com.wxx.aidocumentagent.keyword;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/** 将 ES 客户端异常归一为可决定有限重试策略的安全错误码。 */
@Component
public class KeywordIndexExceptionTranslator {

    public KeywordIndexException translate(Throwable failure) {
        if (failure instanceof KeywordIndexException keywordIndexException) {
            return keywordIndexException;
        }
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof KeywordIndexException keywordIndexException) {
                return keywordIndexException;
            }
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return new KeywordIndexException(KeywordIndexErrorCode.ELASTICSEARCH_TIMEOUT, failure);
            }
            if (current instanceof ElasticsearchException elasticsearchException) {
                return new KeywordIndexException(codeForStatus(elasticsearchException.status()), failure);
            }
            if (current instanceof RestClientResponseException responseException) {
                return new KeywordIndexException(codeForStatus(responseException.getStatusCode().value()), failure);
            }
            if (current instanceof ConnectException || current instanceof IOException) {
                return new KeywordIndexException(KeywordIndexErrorCode.ELASTICSEARCH_UNAVAILABLE, failure);
            }
        }
        return new KeywordIndexException(KeywordIndexErrorCode.ELASTICSEARCH_OPERATION_FAILED, failure);
    }

    public KeywordIndexException bulkItemFailure(int status) {
        KeywordIndexErrorCode errorCode = switch (status) {
            case 408, 504 -> KeywordIndexErrorCode.ELASTICSEARCH_TIMEOUT;
            case 429 -> KeywordIndexErrorCode.ELASTICSEARCH_REJECTED;
            case 401, 403 -> KeywordIndexErrorCode.ELASTICSEARCH_AUTHENTICATION_FAILED;
            default -> status >= 500
                    ? KeywordIndexErrorCode.ELASTICSEARCH_UNAVAILABLE
                    : KeywordIndexErrorCode.ELASTICSEARCH_BULK_ITEM_FAILED;
        };
        return new KeywordIndexException(errorCode);
    }

    private KeywordIndexErrorCode codeForStatus(int status) {
        return switch (status) {
            case 401, 403 -> KeywordIndexErrorCode.ELASTICSEARCH_AUTHENTICATION_FAILED;
            case 408, 504 -> KeywordIndexErrorCode.ELASTICSEARCH_TIMEOUT;
            case 429 -> KeywordIndexErrorCode.ELASTICSEARCH_REJECTED;
            default -> status >= 500
                    ? KeywordIndexErrorCode.ELASTICSEARCH_UNAVAILABLE
                    : KeywordIndexErrorCode.ELASTICSEARCH_OPERATION_FAILED;
        };
    }
}
