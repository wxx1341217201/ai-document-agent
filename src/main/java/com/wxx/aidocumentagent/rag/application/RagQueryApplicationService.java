package com.wxx.aidocumentagent.rag.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
    private final RagContextBuilder contextBuilder;
    private final RagChatClient chatClient;
    private final CitationValidator citationValidator;
    private final RagModelCallAuditService auditService;
    private final RagProperties properties;
    private final RagQueryRetrievalService queryRetrievalService;

    public RagQueryApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                      RagRetrievalGateway retrievalGateway,
                                      RagContextBuilder contextBuilder,
                                      RagChatClient chatClient,
                                      CitationValidator citationValidator,
                                      RagModelCallAuditService auditService,
                                      RagProperties properties) {
        this(knowledgeBaseRepository, retrievalGateway, contextBuilder, chatClient, citationValidator, auditService,
                properties, new RagQueryRetrievalService(retrievalGateway, properties, null));
    }

    public RagQueryApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                      RagRetrievalGateway retrievalGateway, RagContextBuilder contextBuilder,
                                      RagChatClient chatClient, CitationValidator citationValidator,
                                      RagModelCallAuditService auditService, RagProperties properties,
                                      RagQueryRetrievalService queryRetrievalService) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.contextBuilder = contextBuilder;
        this.chatClient = chatClient;
        this.citationValidator = citationValidator;
        this.auditService = auditService;
        this.properties = properties;
        this.queryRetrievalService = queryRetrievalService;
    }

    @Transactional(readOnly = true)
    public RagQueryResponse query(long knowledgeBaseId, RagQueryCommand command) {
        return execute(knowledgeBaseId, command, List.of()).response();
    }

    /**
     * M12 通过该入口提供已经裁剪的用户可见历史。历史不改变检索知识库边界，且始终按不可信数据处理。
     */
    @Transactional(readOnly = true)
    public RagQueryExecution queryForConversation(long knowledgeBaseId, RagQueryCommand command,
                                                   List<RagConversationTurn> conversationHistory) {
        return execute(knowledgeBaseId, command, conversationHistory == null ? List.of() : List.copyOf(conversationHistory));
    }

    private RagQueryExecution execute(long knowledgeBaseId, RagQueryCommand command,
                                      List<RagConversationTurn> conversationHistory) {
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

        var outcome = queryRetrievalService.retrieve(knowledgeBaseId, command.question(), conversationHistory,
                topK, command.rerank());
        RetrievalResult retrieval = outcome.retrieval();
        RagContext context = contextBuilder.build(knowledgeBaseId, command.question(), conversationHistory,
                retrieval.chunks());
        RagRetrievalResponse retrievalResponse = new RagRetrievalResponse(
                outcome.degraded(), context.candidateCount());
        if (!context.hasEvidence()) {
            // 没有可验证 chunk 时明确短路，绝不调用模型强迫它编造答案。
            return execution(response(NO_EVIDENCE_ANSWER, context.citations(), retrievalResponse), null, context, outcome.trace());
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
                return execution(response(UNVERIFIABLE_MODEL_ANSWER, List.of(), retrievalResponse), completion, context, outcome.trace());
            }
            recordSuccess(knowledgeBaseId, traceId, completion, elapsed);
            return execution(response(validation.answer(), validation.citations(), retrievalResponse), completion, context, outcome.trace());
        }
        catch (RagChatException exception) {
            Duration elapsed = elapsedSince(startedAt);
            String errorCode = exception.getReason() == RagChatException.Reason.TIMEOUT
                    ? "CHAT_MODEL_TIMEOUT" : "CHAT_MODEL_FAILURE";
            recordFailure(knowledgeBaseId, traceId, properties.getModel(), null, elapsed, errorCode);
            throw chatFailure(exception);
        }
    }

    private RagQueryExecution execution(RagQueryResponse response, RagChatCompletion completion, RagContext context,
                                        RagRetrievalTrace trace) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (RagPromptTemplates.systemInstruction() + "\n" + context.userPrompt())
                            .getBytes(StandardCharsets.UTF_8)));
            return new RagQueryExecution(response, completion, context.evidence(), hash, trace);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
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
