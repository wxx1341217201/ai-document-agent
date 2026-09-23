package com.wxx.aidocumentagent.retrieval.rerank;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 通用 HTTP Cross-Encoder 客户端。
 *
 * <p>请求契约为 {@code {"query":"...","documents":[{"id":"chunkId","text":"..."}]}}，
 * 响应契约为 {@code {"results":[{"id":"chunkId","score":0.91}]}}。
 * 返回项按 ID 校验为请求候选的完整一一映射，绝不按数组位置关联分数。</p>
 */
public final class HttpCrossEncoderReranker implements Reranker {

    private final RerankingProperties properties;
    private final RestClient restClient;

    public HttpCrossEncoderReranker(RerankingProperties properties) {
        this(properties, createRestClient(properties));
    }

    HttpCrossEncoderReranker(RerankingProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties, "rerankingProperties不能为空");
        this.restClient = Objects.requireNonNull(restClient, "restClient不能为空");
    }

    @Override
    public List<RankedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        NoOpReranker.requireRequest(query, candidates, topN);
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (properties.getUrl() == null) {
            throw new RerankingException(RerankingErrorCode.INVALID_REQUEST);
        }

        List<IndexedCandidate> boundedCandidates = selectCandidates(candidates);
        CrossEncoderRequest request = new CrossEncoderRequest(query, boundedCandidates.stream()
                .map(candidate -> new CrossEncoderDocument(candidate.id(), truncate(candidate.chunk().content())))
                .toList());
        return sendWithRetry(request, boundedCandidates, topN);
    }

    private List<RankedChunk> sendWithRetry(CrossEncoderRequest request, List<IndexedCandidate> candidates, int topN) {
        RerankingException lastFailure = null;
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                return sendOnce(request, candidates, topN);
            }
            catch (RerankingException exception) {
                lastFailure = exception;
                if (!exception.isRetryable() || attempt == properties.getMaxRetries()) {
                    throw exception;
                }
            }
        }
        throw lastFailure == null ? new RerankingException(RerankingErrorCode.INTERNAL_FAILURE) : lastFailure;
    }

    private List<RankedChunk> sendOnce(CrossEncoderRequest request, List<IndexedCandidate> candidates, int topN) {
        try {
            CrossEncoderResponse response = restClient.post()
                    .uri(properties.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(this::applyAuthentication)
                    .body(request)
                    .retrieve()
                    .body(CrossEncoderResponse.class);
            return mapResponse(response, candidates, topN);
        }
        catch (RerankingException exception) {
            throw exception;
        }
        catch (RestClientResponseException exception) {
            throw mapHttpFailure(exception);
        }
        catch (ResourceAccessException exception) {
            throw new RerankingException(isTimeout(exception)
                    ? RerankingErrorCode.REQUEST_TIMEOUT : RerankingErrorCode.REMOTE_UNAVAILABLE);
        }
        catch (RestClientException exception) {
            throw new RerankingException(isResponseDecodingFailure(exception)
                    ? RerankingErrorCode.INVALID_RESPONSE : RerankingErrorCode.REMOTE_UNAVAILABLE);
        }
    }

    private void applyAuthentication(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getApiKey())) {
            String prefix = properties.getApiKeyPrefix() == null ? "" : properties.getApiKeyPrefix();
            headers.set(properties.getApiKeyHeader(), prefix + properties.getApiKey());
        }
    }

    private List<IndexedCandidate> selectCandidates(List<RetrievedChunk> candidates) {
        int size = Math.min(candidates.size(), properties.getMaxCandidates());
        List<IndexedCandidate> selected = new ArrayList<>(size);
        Map<String, IndexedCandidate> seenIds = new HashMap<>(size);
        Long knowledgeBaseId = null;
        for (int index = 0; index < size; index++) {
            RetrievedChunk candidate = candidates.get(index);
            if (knowledgeBaseId == null) {
                knowledgeBaseId = candidate.knowledgeBaseId();
            }
            else if (candidate.knowledgeBaseId() != knowledgeBaseId) {
                throw new RerankingException(RerankingErrorCode.INVALID_REQUEST);
            }
            IndexedCandidate indexed = new IndexedCandidate(Long.toString(candidate.chunkId()), candidate, index + 1);
            if (seenIds.putIfAbsent(indexed.id(), indexed) != null) {
                throw new RerankingException(RerankingErrorCode.INVALID_REQUEST);
            }
            selected.add(indexed);
        }
        return List.copyOf(selected);
    }

    private List<RankedChunk> mapResponse(CrossEncoderResponse response, List<IndexedCandidate> candidates, int topN) {
        if (response == null || response.results() == null) {
            throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
        }

        Map<String, IndexedCandidate> candidatesById = new LinkedHashMap<>();
        for (IndexedCandidate candidate : candidates) {
            candidatesById.put(candidate.id(), candidate);
        }

        Map<String, Double> scoresById = new HashMap<>(candidates.size());
        for (CrossEncoderScore result : response.results()) {
            if (result == null || !StringUtils.hasText(result.id()) || result.score() == null
                    || !Double.isFinite(result.score())) {
                throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
            }
            if (!candidatesById.containsKey(result.id()) || scoresById.putIfAbsent(result.id(), result.score()) != null) {
                throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
            }
        }
        if (scoresById.size() != candidates.size()) {
            throw new RerankingException(RerankingErrorCode.INVALID_RESPONSE);
        }

        List<ScoredCandidate> scored = candidates.stream()
                .map(candidate -> new ScoredCandidate(candidate, scoresById.get(candidate.id())))
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparingInt(value -> value.candidate().inputRank()))
                .toList();
        List<RankedChunk> ranked = new ArrayList<>(Math.min(topN, scored.size()));
        for (int index = 0; index < scored.size() && index < topN; index++) {
            ScoredCandidate candidate = scored.get(index);
            ranked.add(new RankedChunk(candidate.candidate().chunk(), candidate.candidate().inputRank(), index + 1,
                    candidate.score()));
        }
        return List.copyOf(ranked);
    }

    private String truncate(String content) {
        if (content.length() <= properties.getMaxTextLength()) {
            return content;
        }
        int end = properties.getMaxTextLength();
        if (Character.isHighSurrogate(content.charAt(end - 1)) && Character.isLowSurrogate(content.charAt(end))) {
            end--;
        }
        return content.substring(0, end);
    }

    private RerankingException mapHttpFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return new RerankingException(RerankingErrorCode.REMOTE_UNAVAILABLE);
        }
        return new RerankingException(RerankingErrorCode.REMOTE_REJECTED);
    }

    private boolean isTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isResponseDecodingFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpMessageNotReadableException || current instanceof JsonProcessingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RestClient createRestClient(RerankingProperties properties) {
        Objects.requireNonNull(properties, "rerankingProperties不能为空");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private record IndexedCandidate(String id, RetrievedChunk chunk, int inputRank) {
    }

    private record ScoredCandidate(IndexedCandidate candidate, double score) {
    }

    private record CrossEncoderRequest(String query, List<CrossEncoderDocument> documents) {
    }

    private record CrossEncoderDocument(String id, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrossEncoderResponse(List<CrossEncoderScore> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrossEncoderScore(String id, Double score) {
    }
}
