package com.wxx.aidocumentagent.rag.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.api.CommonErrorCode;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.api.dto.RagQueryResponse;
import com.wxx.aidocumentagent.rag.api.dto.RagRetrievalResponse;
import com.wxx.aidocumentagent.rag.domain.RagErrorCode;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * M11 问答编排：先检索、重排、回填可验证证据、构建预算内上下文，再调用 ChatClient。
 * Controller 只负责将 HTTP 请求转换成 {@link RagQueryCommand}。
 */
public class RagQueryApplicationService {

    private static final String NO_EVIDENCE_ANSWER = "我不知道。现有资料不足，请补充相关资料。";
    private static final String UNVERIFIABLE_MODEL_ANSWER = "我无法根据可验证来源生成可靠回答。请重试或补充相关资料。";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagRetrievalGateway retrievalGateway;
    private final RagContextBuilder contextBuilder;
    private final RagChatClient chatClient;
    private final CitationValidator citationValidator;
    private final RagModelCallAuditService auditService;
    private final RagProperties properties;

    public RagQueryApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                      RagRetrievalGateway retrievalGateway,
                                      RagContextBuilder contextBuilder,
                                      RagChatClient chatClient,
                                      CitationValidator citationValidator,
                                      RagModelCallAuditService auditService,
                                      RagProperties properties) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.retrievalGateway = retrievalGateway;
        this.contextBuilder = contextBuilder;
        this.chatClient = chatClient;
        this.citationValidator = citationValidator;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public RagQueryResponse query(long knowledgeBaseId, RagQueryCommand command) {
        if (knowledgeBaseId <= 0L) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        int topK = properties.resolveTopK(command.topK());
        if (topK < 1 || topK > RagProperties.MAX_TOP_K) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }

        RetrievalResult retrieval = retrievalGateway.retrieve(knowledgeBaseId, command.question(), topK,
                command.rerank());
        RagContext context = contextBuilder.build(knowledgeBaseId, command.question(), retrieval.chunks());
        RagRetrievalResponse retrievalResponse = new RagRetrievalResponse(
                retrieval.degraded() || retrieval.rerankingDegraded(), context.candidateCount());
        if (!context.hasEvidence()) {
            // 没有可验证 chunk 时明确短路，绝不调用模型强迫它编造答案。
            return response(NO_EVIDENCE_ANSWER, context.citations(), retrievalResponse);
        }

        long startedAt = System.nanoTime();
        String traceId = traceId();
        try {
            RagChatCompletion completion = chatClient.complete(RagPromptTemplates.systemInstruction(),
                    context.userPrompt());
            Duration elapsed = elapsedSince(startedAt);
            CitationValidation validation = citationValidator.validate(completion.answer(), context.citations());
            if (!validation.valid()) {
                recordFailure(knowledgeBaseId, traceId, completion.modelName(), completion, elapsed,
                        "INVALID_CITATION");
                return response(UNVERIFIABLE_MODEL_ANSWER, List.of(), retrievalResponse);
            }
            recordSuccess(knowledgeBaseId, traceId, completion, elapsed);
            return response(validation.answer(), validation.citations(), retrievalResponse);
        }
        catch (RagChatException exception) {
            Duration elapsed = elapsedSince(startedAt);
            String errorCode = exception.getReason() == RagChatException.Reason.TIMEOUT
                    ? "CHAT_MODEL_TIMEOUT" : "CHAT_MODEL_FAILURE";
            recordFailure(knowledgeBaseId, traceId, properties.getModel(), null, elapsed, errorCode);
            throw chatFailure(exception);
        }
    }

    private RagQueryResponse response(String answer, List<RagCitation> citations, RagRetrievalResponse retrieval) {
        return new RagQueryResponse(answer, citations.stream()
                .map(citation -> new RagCitationResponse(citation.citationId(), citation.documentId(),
                        citation.documentName(), citation.chunkId(), citation.pageFrom(), citation.pageTo(),
                        citation.quote()))
                .toList(), retrieval);
    }

    private void recordSuccess(long knowledgeBaseId, String traceId, RagChatCompletion completion, Duration elapsed) {
        try {
            auditService.recordSuccess(knowledgeBaseId, traceId, completion, elapsed);
        }
        catch (RuntimeException exception) {
            // 审计是合规结果的一部分；落库失败时不返回未经审计的模型结果，也不回显数据库错误。
            throw new BusinessException(RagErrorCode.MODEL_CALL_AUDIT_FAILED);
        }
    }

    private void recordFailure(long knowledgeBaseId, String traceId, String modelName, RagChatCompletion completion,
                               Duration elapsed, String errorCode) {
        try {
            auditService.recordFailure(knowledgeBaseId, traceId, modelName, completion, elapsed, errorCode);
        }
        catch (RuntimeException exception) {
            throw new BusinessException(RagErrorCode.MODEL_CALL_AUDIT_FAILED);
        }
    }

    private BusinessException chatFailure(RagChatException exception) {
        return exception.getReason() == RagChatException.Reason.TIMEOUT
                ? new BusinessException(RagErrorCode.CHAT_MODEL_TIMEOUT)
                : new BusinessException(RagErrorCode.CHAT_MODEL_UNAVAILABLE);
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }

    private String traceId() {
        String traceId = TraceIdFilter.currentTraceId();
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }
}
