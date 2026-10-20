package com.wxx.aidocumentagent.rag.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentConversationSupport;
import com.wxx.aidocumentagent.agent.ClaimEvidenceValidator;
import com.wxx.aidocumentagent.agent.AgentFailure;
import com.wxx.aidocumentagent.agent.EvidenceAnswerSupport;
import com.wxx.aidocumentagent.agent.AgentErrorCode;
import com.wxx.aidocumentagent.agent.AgentEvent;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentMemoryMessage;
import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.AgentRequest;
import com.wxx.aidocumentagent.agent.AgentResult;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentUsage;
import com.wxx.aidocumentagent.agent.DocumentAgent;
import com.wxx.aidocumentagent.agent.RetrievalFirstProperties;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationRepository;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.domain.RagErrorCode;
import com.wxx.aidocumentagent.retrieval.RetrievalResult;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;

/** 固定的检索、证据门控、生成与引用校验流程；没有开放式工具循环。 */
public final class RetrievalFirstAgent implements DocumentAgent {

    private static final String INSUFFICIENT_EVIDENCE = "现有资料不足以回答这个问题，请补充相关资料。";
    private static final String UNVERIFIABLE_ANSWER = "现有资料不足以生成可核验的回答，请补充相关资料。";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ConversationRepository conversationRepository;
    private final RagQueryRetrievalService queryRetrieval;
    private final RagContextBuilder contextBuilder;
    private final RagChatClient chatClient;
    private final RetrievalQuestionRewriter rewriter;
    private final CitationValidator citationValidator;
    private final RagModelCallAuditService auditService;
    private final RagProperties ragProperties;
    private final RetrievalFirstProperties properties;
    private final ExecutorService executor;

    public RetrievalFirstAgent(KnowledgeBaseRepository knowledgeBaseRepository,
                               ConversationRepository conversationRepository, RagRetrievalGateway retrievalGateway,
                               RagContextBuilder contextBuilder, RagChatClient chatClient,
                               RetrievalQuestionRewriter rewriter, CitationValidator citationValidator,
                               RagModelCallAuditService auditService, RagProperties ragProperties,
                               RetrievalFirstProperties properties, ExecutorService executor) {
        this.knowledgeBaseRepository = Objects.requireNonNull(knowledgeBaseRepository);
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.queryRetrieval = new RagQueryRetrievalService(Objects.requireNonNull(retrievalGateway), ragProperties, executor);
        this.contextBuilder = Objects.requireNonNull(contextBuilder);
        this.chatClient = Objects.requireNonNull(chatClient);
        this.rewriter = Objects.requireNonNull(rewriter);
        this.citationValidator = Objects.requireNonNull(citationValidator);
        this.auditService = Objects.requireNonNull(auditService);
        this.ragProperties = Objects.requireNonNull(ragProperties);
        this.properties = Objects.requireNonNull(properties);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public AgentMode mode() {
        return AgentMode.RETRIEVAL_FIRST;
    }

    @Override
    public AgentResult execute(AgentRequest request, AgentExecutionContext context) {
        context.checkActive();
        if (request.mode() != null && request.mode() != mode()) {
            throw new BusinessException(AgentErrorCode.INVALID_MODE);
        }
        if (request.question().length() > 2_000) {
            throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
        if (!withinDeadline(context, () -> knowledgeBaseRepository.existsById(request.knowledgeBaseId()))) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (request.conversationId() != null && !withinDeadline(context,
                () -> conversationRepository.existsByIdAndKnowledgeBaseIdAndAgentMode(request.conversationId(),
                        request.knowledgeBaseId(), ConversationAgentMode.RETRIEVAL_FIRST))) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND);
        }
        context.publish(mode(), AgentEvent.Kind.STARTED, null, null);
        List<RagConversationTurn> history = boundedHistory(context.memory().messages());
        ModelUse use = new ModelUse();
        String retrievalQuestion = rewriteIfUseful(request, context, history, use);

        context.checkActive();
        context.publish(mode(), AgentEvent.Kind.RETRIEVING, "retrieve", null);
        var retrievalOutcome = withinDeadline(context, () -> queryRetrieval.retrieve(request.knowledgeBaseId(),
                retrievalQuestion, retrievalQuestion.equals(request.question()) ? history : List.of(),
                properties.getTopK(), properties.isRerank()));
        RetrievalResult retrieval = retrievalOutcome.retrieval();
        context.diagnostic("retrieval", Map.of("trace", retrievalOutcome.trace(),
                "selectedChunkIds", retrieval.chunks().stream().map(RetrievedChunk::chunkId).toList()));
        context.checkActive();
        List<RetrievedChunk> eligible = retrievalOutcome.candidates().stream()
                .filter(chunk -> chunk.knowledgeBaseId() == request.knowledgeBaseId()
                        && chunk.fusedScore() >= properties.getMinFusedScore())
                .toList();
        RagContext evidence = withinDeadline(context, () -> contextBuilder.build(request.knowledgeBaseId(),
                request.question(), history, eligible, Map.of(), properties.isStructuredAnswers() ? 1_024 : 0));
        context.checkActive();
        context.diagnostic("context_selection", Map.of("eligibleChunkIds", eligible.stream().map(RetrievedChunk::chunkId).toList(),
                "visibleChunkIds", evidence.evidence().stream().map(snapshot -> snapshot.citation().chunkId()).toList(),
                "checks", "PERSISTED_READY_KB_DOCUMENT_INTENT_CHARACTER_AND_TOKEN_BUDGET"));
        for (RagEvidenceChunk snapshot : evidence.evidence()) {
            var citation = snapshot.citation();
            context.observeEvidence(new AgentCitation(snapshot.knowledgeBaseId(), citation.citationId(),
                    citation.documentId(), citation.documentName(), citation.chunkId(), citation.pageFrom(),
                    citation.pageTo(), citation.quote()), snapshot.contextText());
        }
        context.checkpointStep(new AgentStepSummary("retrieve", AgentStepSummary.Kind.RETRIEVAL,
                "hybrid_retrieve", AgentStepSummary.Status.COMPLETED, null));
        if (evidence.citations().size() < properties.getMinEvidenceChunks()) {
            context.checkpointStep(new AgentStepSummary("generate", AgentStepSummary.Kind.GENERATION,
                    "cited_generate", AgentStepSummary.Status.SKIPPED, null));
            return result(INSUFFICIENT_EVIDENCE, List.of(), retrievalSteps(true), use, null);
        }

        context.publish(mode(), AgentEvent.Kind.GENERATING, "generate", null);
        RagChatCompletion completion = generate(request, context, evidence, use);
        context.checkActive();
        if (properties.isStructuredAnswers()) return groundedResult(request, context, evidence, eligible, completion, use);
        CitationValidation validation = citationValidator.validate(completion.answer(), evidence.citations());
        if (!validation.valid()) {
            auditFailure(request, context, completion.modelName(), completion, use.lastElapsed, "INVALID_CITATION");
            context.checkpointStep(new AgentStepSummary("generate", AgentStepSummary.Kind.GENERATION,
                    "cited_generate", AgentStepSummary.Status.COMPLETED, null));
            return result(UNVERIFIABLE_ANSWER, List.of(), retrievalSteps(false), use, completion.modelName());
        }
        auditSuccess(request, context, completion, use.lastElapsed);
        List<AgentCitation> citations = validation.citations().stream()
                .map(citation -> new AgentCitation(request.knowledgeBaseId(), citation.citationId(),
                        citation.documentId(), citation.documentName(), citation.chunkId(), citation.pageFrom(),
                        citation.pageTo(), citation.quote()))
                .toList();
        context.checkActive();
        context.checkpointStep(new AgentStepSummary("generate", AgentStepSummary.Kind.GENERATION,
                "cited_generate", AgentStepSummary.Status.COMPLETED, null));
        return result(validation.answer(), citations, retrievalSteps(false), use, completion.modelName());
    }

    private String rewriteIfUseful(AgentRequest request, AgentExecutionContext context,
                                   List<RagConversationTurn> history, ModelUse use) {
        String original = request.question();
        if (properties.isStructuredAnswers() || !properties.isRewriteEnabled() || history.isEmpty() || !needsRewrite(original)) {
            return original;
        }
        long startedAt = System.nanoTime();
        use.modelCalls++;
        try {
            RagChatCompletion completion = withinDeadline(context, () -> rewriter.rewrite(original, history));
            Duration elapsed = elapsedSince(startedAt);
            auditSuccess(request, context, completion, elapsed);
            use.add(completion);
            String candidate = completion.answer().strip();
            String prefix = candidate.endsWith(original)
                    ? candidate.substring(0, candidate.length() - original.length()) : "";
            if (candidate.length() > 2_000 || !prefix.endsWith("；") || prefix.length() < 2 || prefix.length() > 81
                    || prefix.indexOf('\n') >= 0 || prefix.indexOf('\r') >= 0) {
                return original;
            }
            String topic = prefix.substring(0, prefix.length() - 1);
            return history.stream().anyMatch(turn -> turn.role() == RagConversationTurn.Role.USER
                    && turn.content().contains(topic)) ? candidate : original;
        }
        catch (BusinessException exception) {
            if (exception.getErrorCode() == RagErrorCode.MODEL_CALL_AUDIT_FAILED) {
                throw exception;
            }
            use.markUnknown();
            auditFailure(request, context, ragProperties.getModel(), null, elapsedSince(startedAt), "REWRITE_FAILED");
            context.checkActive();
            return original;
        }
        catch (RagChatException exception) {
            use.markUnknown();
            auditFailure(request, context, ragProperties.getModel(), null, elapsedSince(startedAt), "REWRITE_FAILED");
            context.checkActive();
            return original;
        }
        catch (RuntimeException exception) {
            use.markUnknown();
            auditFailure(request, context, ragProperties.getModel(), null, elapsedSince(startedAt), "REWRITE_FAILED");
            context.checkActive();
            return original;
        }
    }

    private RagChatCompletion generate(AgentRequest request, AgentExecutionContext context, RagContext evidence,
                                       ModelUse use) {
        String system = RagPromptTemplates.systemInstruction() + (properties.isStructuredAnswers()
                ? "\nThe following JSON protocol supersedes the prose output format above.\n" + ClaimEvidenceValidator.INSTRUCTION
                        + EvidenceAnswerSupport.retrievalLanguageHint(evidence.evidence().stream().map(RagEvidenceChunk::contextText).toList()) : "");
        return generatePrompt(request, context, system, evidence.userPrompt(), use,
                evidence.evidence());
    }

    private AgentResult groundedResult(AgentRequest request, AgentExecutionContext context, RagContext evidence,
                                       List<RetrievedChunk> originalCandidates, RagChatCompletion completion, ModelUse use) {
        var bound = new ClaimEvidenceValidator().validate(completion.answer(), request.knowledgeBaseId(), visible(evidence, request.knowledgeBaseId()));
        context.diagnostic("claim_binding", Map.of("valid", bound.valid(), "error", bound.error() == null ? "NONE" : bound.error().name(),
                "bindings", bound.bindings(), "missing", bound.missing()));
        auditSuccess(request, context, completion, use.lastElapsed);
        if (use.modelCalls < properties.getMaxModelCalls()) {
            if (bound.valid() && !bound.missing().isEmpty()) {
                var history = AgentConversationSupport.history(context.memory().messages());
                String query = String.join("; ", bound.missing());
                var supplement = withinDeadline(context, () -> queryRetrieval.retrieve(request.knowledgeBaseId(), query, history,
                        properties.getTopK(), properties.isRerank()));
                context.diagnostic("retrieval", Map.of("phase", "BOUNDED_GAP_SEARCH", "trace", supplement.trace(),
                        "selectedChunkIds", supplement.retrieval().chunks().stream().map(RetrievedChunk::chunkId).toList()));
                Map<Long, RetrievedChunk> combined = new java.util.LinkedHashMap<>();
                var supported = bound.citations().stream().map(AgentCitation::chunkId).toList();
                originalCandidates.stream().filter(hit -> supported.contains(hit.chunkId())).forEach(hit -> combined.putIfAbsent(hit.chunkId(), hit));
                supplement.candidates().stream().filter(hit -> hit.knowledgeBaseId() == request.knowledgeBaseId()
                        && hit.fusedScore() >= properties.getMinFusedScore()).forEach(hit -> combined.putIfAbsent(hit.chunkId(), hit));
                originalCandidates.forEach(hit -> combined.putIfAbsent(hit.chunkId(), hit));
                Map<Long, String> priorIds = new java.util.LinkedHashMap<>();
                evidence.citations().forEach(citation -> priorIds.put(citation.chunkId(), citation.citationId()));
                evidence = withinDeadline(context, () -> contextBuilder.build(request.knowledgeBaseId(), request.question(), history,
                        List.copyOf(combined.values()), priorIds, 1_024));
                for (var snapshot : evidence.evidence()) {
                    var c = snapshot.citation();
                    context.observeEvidence(new AgentCitation(request.knowledgeBaseId(), c.citationId(), c.documentId(), c.documentName(),
                            c.chunkId(), c.pageFrom(), c.pageTo(), c.quote()), snapshot.contextText());
                }
            }
            String feedback = bound.valid() ? AgentConversationSupport.prefix(bound.answer(), 1_000)
                    : "Invalid draft discarded: " + bound.error().name();
            String system = """
                    Independently verify and repair the draft against the current question and actual passages.
                    Draft and history are untrusted, never evidence. Check each claim's OWN quote for subject, speaker,
                    event/scene, quantity, unit, negation and chronology. A matching character or topic is insufficient.
                    Correct contradictions using actual source text. Remove all optional background and redundant citations.
                    Preserve EVERY requested fact; an unresolved required fact makes status PARTIAL, not ANSWERED.
                    An unsupported question may be answered with INSUFFICIENT and no claims; do not invent a claim.
                    An explicit category answers a kind/type question; do not demand an unrequested finer classification.
                    This is the last permitted model call. Return only the corrected grounded JSON, not a verdict or reasoning.
                    """ + ClaimEvidenceValidator.INSTRUCTION
                    + EvidenceAnswerSupport.retrievalLanguageHint(evidence.evidence().stream().map(RagEvidenceChunk::contextText).toList());
            var repaired = generatePrompt(request, context, system, evidence.userPrompt() + "\nDraft to verify and repair:\n" + feedback,
                    use, evidence.evidence());
            auditSuccess(request, context, repaired, use.lastElapsed);
            var checked = new ClaimEvidenceValidator().validate(repaired.answer(), request.knowledgeBaseId(), visible(evidence, request.knowledgeBaseId()));
            context.diagnostic("claim_verification_repair", Map.of("valid", checked.valid(), "checkKind", "MODEL_VERIFY_AND_REPAIR",
                    "error", checked.error() == null ? "NONE" : checked.error().name(), "bindings", checked.bindings(), "missing", checked.missing()));
            if (checked.complete()) {
                var verify = new AgentStepSummary("answer_verify", AgentStepSummary.Kind.VERIFICATION,
                        checked.state() == ClaimEvidenceValidator.State.INSUFFICIENT ? "evidence_insufficient" : "facts_verified_and_bound",
                        AgentStepSummary.Status.COMPLETED, null);
                context.checkpointStep(verify);
                var steps = new ArrayList<>(retrievalSteps(false));
                steps.add(verify);
                return result(checked.answer(), checked.citations(), steps, use, repaired.modelName());
            }
        }
        var failedStep = new AgentStepSummary("answer_verify", AgentStepSummary.Kind.VERIFICATION,
                "evidence_gap", AgentStepSummary.Status.FAILED, AgentErrorCode.INVALID_ANSWER);
        context.checkpointStep(failedStep);
        return new AgentResult(null, List.of(), List.of(retrievalSteps(false).getFirst(), failedStep), List.of(),
                AgentResult.Status.FAILED, new AgentUsage(use.promptKnown ? use.promptTokens : null,
                use.completionKnown ? use.completionTokens : null, use.totalKnown ? use.totalTokens : null, use.modelCalls, 0),
                new AgentFailure(AgentErrorCode.INVALID_ANSWER), completion.modelName());
    }

    private List<ClaimEvidenceValidator.Visible> visible(RagContext evidence, long kb) {
        return evidence.evidence().stream().map(snapshot -> {
            var c = snapshot.citation();
            return new ClaimEvidenceValidator.Visible(new AgentCitation(kb, c.citationId(), c.documentId(), c.documentName(), c.chunkId(),
                    c.pageFrom(), c.pageTo(), c.quote()), snapshot.contextText(), snapshot.sourceContentSha256());
        }).toList();
    }

    private RagChatCompletion generatePrompt(AgentRequest request, AgentExecutionContext context, String system,
                                             String user, ModelUse use, List<RagEvidenceChunk> snapshots) {
        if (use.modelCalls >= properties.getMaxModelCalls()) throw new BusinessException(AgentErrorCode.MODEL_CALL_LIMIT_EXCEEDED);
        if (properties.isStructuredAnswers()) {
            var estimator = new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator();
            if (estimator.estimate(system) + estimator.estimate(user) > ragProperties.inputTokenBudget())
                throw new BusinessException(AgentErrorCode.INVALID_CONTEXT);
        }
        context.observeModelInput(properties.isStructuredAnswers() ? use.modelCalls == 0 ? "RF_DRAFT" : "RF_VERIFY_AND_REPAIR"
                : "RETRIEVAL_FIRST", system, user, snapshots.stream().map(source -> source.citation().citationId()).toList(),
                snapshots.stream().map(source -> Map.<String, Object>of("citationId", source.citation().citationId(),
                        "kind", "SOURCE_PREFIX", "chunkId", source.citation().chunkId(), "characters", source.contextText().length(),
                        "sha256", ClaimEvidenceValidator.sha256(source.contextText()))).toList());
        long startedAt = System.nanoTime();
        use.modelCalls++;
        try {
            RagChatCompletion completion = withinDeadline(context, () -> chatClient.complete(
                    system, user));
            use.lastElapsed = elapsedSince(startedAt);
            use.add(completion);
            return completion;
        }
        catch (RagChatException exception) {
            auditFailure(request, context, ragProperties.getModel(), null, elapsedSince(startedAt),
                    exception.getReason() == RagChatException.Reason.TIMEOUT ? "CHAT_MODEL_TIMEOUT" : "CHAT_MODEL_FAILURE");
            context.checkActive();
            throw new BusinessException(exception.getReason() == RagChatException.Reason.TIMEOUT
                    ? AgentErrorCode.MODEL_TIMEOUT : AgentErrorCode.MODEL_UNAVAILABLE);
        }
        catch (BusinessException exception) {
            if (exception.getErrorCode() != RagErrorCode.MODEL_CALL_AUDIT_FAILED) {
                auditFailure(request, context, ragProperties.getModel(), null, elapsedSince(startedAt),
                        "CHAT_MODEL_DEADLINE");
            }
            throw exception;
        }
    }

    private List<RagConversationTurn> boundedHistory(List<AgentMemoryMessage> messages) {
        List<RagConversationTurn> newestFirst = new ArrayList<>();
        int characters = 0;
        for (int i = messages.size() - 1; i >= 0 && newestFirst.size() < properties.getMaxMemoryMessages(); i--) {
            AgentMemoryMessage message = messages.get(i);
            if (characters + message.content().length() > properties.getMaxMemoryCharacters()) {
                break;
            }
            characters += message.content().length();
            newestFirst.add(new RagConversationTurn(message.role() == AgentMemoryMessage.Role.USER
                    ? RagConversationTurn.Role.USER : RagConversationTurn.Role.ASSISTANT, message.content()));
        }
        return newestFirst.reversed();
    }

    private boolean needsRewrite(String question) {
        return question.length() <= 24 || question.matches("(?is).*(它|他|她|其|该|上述|这个|那个|这些|那些|\\b(it|its|he|she|him|her|his|they|them|their|this|that|these|those)\\b).*");
    }

    private <T> T withinDeadline(AgentExecutionContext context, java.util.concurrent.Callable<T> call) {
        context.checkActive();
        Future<T> task = executor.submit(call);
        try {
            return task.get(context.remainingTime().toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException exception) {
            task.cancel(true);
            throw new BusinessException(AgentErrorCode.DEADLINE_EXCEEDED);
        }
        catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(AgentErrorCode.EXECUTION_CANCELLED);
        }
        catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BusinessException(AgentErrorCode.EXECUTION_FAILED);
        }
    }

    private void auditSuccess(AgentRequest request, AgentExecutionContext context, RagChatCompletion completion,
                              Duration elapsed) {
        try {
            auditService.recordSuccess(request.knowledgeBaseId(), context.traceId(), completion, elapsed);
        }
        catch (RuntimeException exception) {
            throw new BusinessException(RagErrorCode.MODEL_CALL_AUDIT_FAILED);
        }
    }

    private void auditFailure(AgentRequest request, AgentExecutionContext context, String modelName,
                              RagChatCompletion completion, Duration elapsed, String errorCode) {
        try {
            auditService.recordFailure(request.knowledgeBaseId(), context.traceId(), modelName, completion,
                    elapsed, errorCode);
        }
        catch (RuntimeException exception) {
            throw new BusinessException(RagErrorCode.MODEL_CALL_AUDIT_FAILED);
        }
    }

    private AgentResult result(String answer, List<AgentCitation> citations, List<AgentStepSummary> steps,
                               ModelUse use, String modelName) {
        return new AgentResult(answer, citations, steps, List.of(), AgentResult.Status.COMPLETED,
                new AgentUsage(use.modelCalls == 0 || !use.promptKnown ? null : use.promptTokens,
                        use.modelCalls == 0 || !use.completionKnown ? null : use.completionTokens,
                        use.modelCalls == 0 || !use.totalKnown ? null : use.totalTokens, use.modelCalls, 0),
                null, modelName);
    }

    private List<AgentStepSummary> retrievalSteps(boolean generationSkipped) {
        return List.of(new AgentStepSummary("retrieve", AgentStepSummary.Kind.RETRIEVAL, "hybrid_retrieve",
                        AgentStepSummary.Status.COMPLETED, null),
                new AgentStepSummary("generate", AgentStepSummary.Kind.GENERATION, "cited_generate",
                        generationSkipped ? AgentStepSummary.Status.SKIPPED : AgentStepSummary.Status.COMPLETED, null));
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static final class ModelUse {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private boolean promptKnown = true;
        private boolean completionKnown = true;
        private boolean totalKnown = true;
        private int modelCalls;
        private Duration lastElapsed = Duration.ZERO;

        private void add(RagChatCompletion completion) {
            if (completion.promptTokens() == null) promptKnown = false;
            else promptTokens += completion.promptTokens();
            if (completion.completionTokens() == null) completionKnown = false;
            else completionTokens += completion.completionTokens();
            if (completion.totalTokens() == null) totalKnown = false;
            else totalTokens += completion.totalTokens();
        }

        private void markUnknown() {
            promptKnown = false;
            completionKnown = false;
            totalKnown = false;
        }
    }
}
