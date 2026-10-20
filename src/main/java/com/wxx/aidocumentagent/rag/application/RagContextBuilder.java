package com.wxx.aidocumentagent.rag.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.parser.ExtractedTextQuality;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.domain.RagErrorCode;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * 将索引命中重新约束到当前知识库的持久化 document_chunk/document，再生成稳定 C#。
 * 索引内容只作为候选定位信息，绝不直接信任其文档名、页码或正文。
 */
public final class RagContextBuilder {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties properties;
    private final TokenCountEstimator tokenCountEstimator;

    public RagContextBuilder(DocumentRepository documentRepository, DocumentChunkRepository documentChunkRepository,
                             RagProperties properties) {
        this(documentRepository, documentChunkRepository, properties, new JTokkitTokenCountEstimator());
    }

    public RagContextBuilder(DocumentRepository documentRepository, DocumentChunkRepository documentChunkRepository,
                             RagProperties properties, TokenCountEstimator tokenCountEstimator) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository不能为空");
        this.documentChunkRepository = Objects.requireNonNull(documentChunkRepository, "documentChunkRepository不能为空");
        this.properties = Objects.requireNonNull(properties, "ragProperties不能为空");
        this.tokenCountEstimator = Objects.requireNonNull(tokenCountEstimator, "tokenCountEstimator不能为空");
    }

    RagContext build(long knowledgeBaseId, String question, List<RetrievedChunk> retrievedChunks) {
        return build(knowledgeBaseId, question, List.of(), retrievedChunks);
    }

    RagContext build(long knowledgeBaseId, String question, List<RagConversationTurn> conversationHistory,
                     List<RetrievedChunk> retrievedChunks) {
        return build(knowledgeBaseId, question, conversationHistory, retrievedChunks, Map.of());
    }

    RagContext build(long knowledgeBaseId, String question, List<RagConversationTurn> conversationHistory,
                     List<RetrievedChunk> retrievedChunks, Map<Long, String> priorIds) {
        return build(knowledgeBaseId, question, conversationHistory, retrievedChunks, priorIds, 0);
    }

    RagContext build(long knowledgeBaseId, String question, List<RagConversationTurn> conversationHistory,
                     List<RetrievedChunk> retrievedChunks, Map<Long, String> priorIds, int instructionReserveTokens) {
        List<RagConversationTurn> history = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        List<RagPromptTemplates.ContextCandidate> candidates = rehydrateCandidates(knowledgeBaseId, retrievedChunks);
        candidates = intentCandidates(knowledgeBaseId, question, candidates);
        var readableCandidates = candidates.stream()
                .filter(candidate -> !ExtractedTextQuality.isUnreadable(candidate.content())).toList();
        if (!candidates.isEmpty() && readableCandidates.isEmpty()) {
            throw new BusinessException(RagErrorCode.UNREADABLE_EVIDENCE);
        }
        candidates = readableCandidates;
        if (candidates.isEmpty()) {
            return new RagContext(RagPromptTemplates.userPrompt(question, history, List.of()), List.of(), 0);
        }

        int fixedPromptTokens = tokenCountEstimator.estimate(RagPromptTemplates.systemInstruction())
                + tokenCountEstimator.estimate(RagPromptTemplates.userPromptWithoutEvidence(question, history));
        int evidenceTokenBudget = Math.max(0, properties.inputTokenBudget() - fixedPromptTokens - instructionReserveTokens);
        if (evidenceTokenBudget <= 0) {
            return new RagContext(RagPromptTemplates.userPrompt(question, history, List.of()), List.of(),
                    candidates.size());
        }

        Selection selection = new Selection(properties.getMaxContextCharacters(), evidenceTokenBudget, priorIds);
        Set<Long> documentsRepresented = new HashSet<>();
        // 第一轮优先每个来源文档的最高排名 chunk，避免单一长文档垄断上下文。
        for (RagPromptTemplates.ContextCandidate candidate : candidates) {
            if (!documentsRepresented.contains(candidate.documentId()) && tryAdd(candidate, selection)) {
                documentsRepresented.add(candidate.documentId());
            }
        }
        // 第二轮按检索排名补齐，只删除正文已经完整出现的重复候选。
        for (RagPromptTemplates.ContextCandidate candidate : candidates) {
            if (!selection.containsChunk(candidate.chunkId()) && !isRedundant(candidate, selection.evidence())) {
                tryAdd(candidate, selection);
            }
        }

        var prefixes = EvidenceOverlap.prefixes(selection.evidence().stream().map(item -> new EvidenceOverlap.Part(
                item.citation().chunkId(), item.citation().documentId(), item.contextContent(), item.citation().quote().length())).toList());
        List<RagPromptTemplates.ContextEvidence> visible = selection.evidence().stream().map(item ->
                new RagPromptTemplates.ContextEvidence(item.citation(), prefixes.get(item.citation().chunkId()), item.candidate())).toList();
        List<RagCitation> citations = visible.stream()
                .map(RagPromptTemplates.ContextEvidence::citation)
                .toList();
        return new RagContext(RagPromptTemplates.userPrompt(question, history, visible), citations,
                candidates.size(), visible.stream().map(item -> snapshot(knowledgeBaseId, item)).toList());
    }

    private List<RagPromptTemplates.ContextCandidate> intentCandidates(long kb, String question,
            List<RagPromptTemplates.ContextCandidate> candidates) {
        boolean named = !EvidenceRelevance.comparison(question) && candidates.stream()
                .anyMatch(candidate -> EvidenceRelevance.documentAffinity(question, candidate.documentName()) > 0);
        List<RagPromptTemplates.ContextCandidate> scoped = named ? candidates.stream()
                .filter(candidate -> EvidenceRelevance.documentAffinity(question, candidate.documentName()) > 0).toList() : candidates;
        if (!EvidenceRelevance.metadata(question)) return scoped;
        Map<Long, RagPromptTemplates.ContextCandidate> result = new LinkedHashMap<>();
        for (var document : scoped.stream().map(RagPromptTemplates.ContextCandidate::documentId).distinct().limit(2).toList()) {
            var sample = scoped.stream().filter(candidate -> candidate.documentId() == document).findFirst().orElseThrow();
            for (var header : documentChunkRepository.findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(kb, document, 0, 1)) {
                if (isChunkWithinKnowledgeBase(header, kb, document) && header.getContent() != null
                        && !header.getContent().isBlank() && !ExtractedTextQuality.isUnreadable(header.getContent()))
                    result.putIfAbsent(header.getId(), new RagPromptTemplates.ContextCandidate(0, header.getId(), document,
                            header.getChunkIndex(), sample.documentName(), header.getContent(), header.getPageFrom(), header.getPageTo()));
            }
        }
        scoped.forEach(candidate -> result.putIfAbsent(candidate.chunkId(), candidate));
        return List.copyOf(result.values());
    }

    private RagEvidenceChunk snapshot(long knowledgeBaseId, RagPromptTemplates.ContextEvidence item) {
        RagCitation citation = item.citation();
        RagCitationResponse reference = new RagCitationResponse(citation.citationId(), citation.documentId(),
                citation.documentName(), citation.chunkId(), citation.pageFrom(), citation.pageTo(), citation.quote());
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(item.candidate().content().getBytes(StandardCharsets.UTF_8)));
            return new RagEvidenceChunk(knowledgeBaseId, reference, item.contextContent(), hash);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private List<RagPromptTemplates.ContextCandidate> rehydrateCandidates(long knowledgeBaseId,
                                                                            List<RetrievedChunk> retrievedChunks) {
        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            return List.of();
        }
        Map<Long, RetrievedChunk> uniqueScopedHits = new LinkedHashMap<>();
        for (RetrievedChunk hit : retrievedChunks) {
            if (hit != null && hit.knowledgeBaseId() == knowledgeBaseId && hit.chunkId() > 0L
                    && hit.documentId() > 0L) {
                uniqueScopedHits.putIfAbsent(hit.chunkId(), hit);
            }
        }
        if (uniqueScopedHits.isEmpty()) {
            return List.of();
        }

        Map<Long, DocumentChunk> chunksById = byId(documentChunkRepository.findByKnowledgeBaseIdAndIdIn(
                knowledgeBaseId, uniqueScopedHits.keySet()), DocumentChunk::getId);
        if (chunksById.isEmpty()) {
            return List.of();
        }
        Set<Long> documentIds = new HashSet<>();
        for (RetrievedChunk hit : uniqueScopedHits.values()) {
            DocumentChunk chunk = chunksById.get(hit.chunkId());
            if (isChunkWithinKnowledgeBase(chunk, knowledgeBaseId, hit.documentId())) {
                documentIds.add(hit.documentId());
            }
        }
        if (documentIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Document> documentsById = byId(documentRepository.findByKnowledgeBaseIdAndIdIn(
                knowledgeBaseId, documentIds), Document::getId);

        List<RagPromptTemplates.ContextCandidate> candidates = new ArrayList<>();
        int rank = 0;
        for (RetrievedChunk hit : uniqueScopedHits.values()) {
            rank++;
            DocumentChunk chunk = chunksById.get(hit.chunkId());
            Document document = documentsById.get(hit.documentId());
            if (!isChunkWithinKnowledgeBase(chunk, knowledgeBaseId, hit.documentId())
                    || !isDocumentWithinKnowledgeBase(document, knowledgeBaseId)
                    || !Objects.equals(chunk.getDocumentId(), document.getId())) {
                continue;
            }
            String documentName = safeDocumentName(document.getOriginalName());
            String content = chunk.getContent();
            if (documentName == null || content == null || content.isBlank()) {
                continue;
            }
            candidates.add(new RagPromptTemplates.ContextCandidate(rank, chunk.getId(), document.getId(),
                    chunk.getChunkIndex(), documentName, content, chunk.getPageFrom(), chunk.getPageTo()));
        }
        return List.copyOf(candidates);
    }

    private boolean tryAdd(RagPromptTemplates.ContextCandidate candidate, Selection selection) {
        int contentLength = codePointCount(candidate.content());
        int minimumContent = Math.min(contentLength, properties.getMinChunkCharacters());
        String citationId = selection.citationId(candidate.chunkId());
        String quote = truncateCodePoints(candidate.content(), properties.getQuoteMaxCharacters());
        RagCitation citation = new RagCitation(citationId, candidate.documentId(), candidate.documentName(),
                candidate.chunkId(), candidate.pageFrom(), candidate.pageTo(), quote);
        int blockOverhead = codePointCount(RagPromptTemplates.evidenceBlock(citation, ""));
        int availableForContent = Math.min(properties.getMaxChunkCharacters(),
                selection.remainingCharacters() - blockOverhead);
        if (availableForContent < minimumContent) {
            return false;
        }
        String contextContent = fitWithinTokenBudget(citation, candidate.content(), availableForContent,
                selection.remainingTokens());
        if (contextContent == null || codePointCount(contextContent) < minimumContent) {
            return false;
        }
        RagPromptTemplates.ContextEvidence evidence = new RagPromptTemplates.ContextEvidence(citation, contextContent,
                candidate);
        int actualBlockLength = codePointCount(RagPromptTemplates.evidenceBlock(citation, contextContent));
        int actualBlockTokens = tokenCountEstimator.estimate(RagPromptTemplates.evidenceBlock(citation, contextContent));
        if (actualBlockLength > selection.remainingCharacters() || actualBlockTokens > selection.remainingTokens()) {
            return false;
        }
        selection.add(evidence, actualBlockLength, actualBlockTokens);
        return true;
    }

    private String fitWithinTokenBudget(RagCitation citation, String content, int maximumCodePoints,
                                        int remainingTokens) {
        String boundedByCharacters = truncateCodePoints(content, maximumCodePoints);
        if (tokenCountEstimator.estimate(RagPromptTemplates.evidenceBlock(citation, boundedByCharacters))
                <= remainingTokens) {
            return boundedByCharacters;
        }
        int low = 0;
        int high = codePointCount(boundedByCharacters);
        int best = -1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            String attempt = truncateCodePoints(boundedByCharacters, middle);
            if (tokenCountEstimator.estimate(RagPromptTemplates.evidenceBlock(citation, attempt)) <= remainingTokens) {
                best = middle;
                low = middle + 1;
            }
            else {
                high = middle - 1;
            }
        }
        return best < 0 ? null : truncateCodePoints(boundedByCharacters, best);
    }

    private boolean isRedundant(RagPromptTemplates.ContextCandidate candidate,
                                List<RagPromptTemplates.ContextEvidence> selectedEvidence) {
        for (RagPromptTemplates.ContextEvidence selected : selectedEvidence) {
            RagPromptTemplates.ContextCandidate selectedCandidate = selected.candidate();
            if (candidate.documentId() != selectedCandidate.documentId()) {
                continue;
            }
            // 只去除已经完整出现在实际上下文中的正文；共享边界不能授权删除新增事实。
            if (selected.contextContent().contains(candidate.content())) return true;
        }
        return false;
    }

    private static boolean isChunkWithinKnowledgeBase(DocumentChunk chunk, long knowledgeBaseId, long documentId) {
        return chunk != null && chunk.getId() != null && chunk.getKnowledgeBaseId() != null
                && chunk.getDocumentId() != null && chunk.getKnowledgeBaseId() == knowledgeBaseId
                && chunk.getDocumentId() == documentId;
    }

    private static boolean isDocumentWithinKnowledgeBase(Document document, long knowledgeBaseId) {
        return document != null && document.getId() != null && document.getKnowledgeBaseId() != null
                && document.getStatus() == DocumentStatus.READY
                && document.getKnowledgeBaseId() == knowledgeBaseId;
    }

    private static <T> Map<Long, T> byId(Collection<T> values, java.util.function.Function<T, Long> idExtractor) {
        Map<Long, T> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (T value : values) {
            if (value != null) {
                Long id = idExtractor.apply(value);
                if (id != null && id > 0L) {
                    result.putIfAbsent(id, value);
                }
            }
        }
        return result;
    }

    private static String safeDocumentName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        String result = sanitized.toString().strip();
        return result.isBlank() ? null : result;
    }

    private static String truncateCodePoints(String value, int maximumCodePoints) {
        int actualLength = codePointCount(value);
        if (actualLength <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static final class Selection {

        private final List<RagPromptTemplates.ContextEvidence> evidence = new ArrayList<>();
        private int remainingCharacters;
        private int remainingTokens;
        private final Map<Long, String> citationIds;
        private int nextId;

        private Selection(int remainingCharacters, int remainingTokens, Map<Long, String> priorIds) {
            this.remainingCharacters = remainingCharacters;
            this.remainingTokens = remainingTokens;
            this.citationIds = new LinkedHashMap<>(priorIds);
            this.nextId = priorIds.values().stream().mapToInt(id -> Integer.parseInt(id.substring(1))).max().orElse(0);
        }

        private String citationId(long chunkId) { return citationIds.getOrDefault(chunkId, "C" + (nextId + 1)); }

        private boolean containsChunk(long chunkId) {
            return evidence.stream().anyMatch(item -> item.citation().chunkId() == chunkId);
        }

        private void add(RagPromptTemplates.ContextEvidence item, int characterCost, int tokenCost) {
            citationIds.putIfAbsent(item.citation().chunkId(), item.citation().citationId());
            nextId = Math.max(nextId, Integer.parseInt(item.citation().citationId().substring(1)));
            evidence.add(item);
            remainingCharacters -= characterCost;
            remainingTokens -= tokenCost;
        }

        private int remainingCharacters() {
            return remainingCharacters;
        }

        private int remainingTokens() {
            return remainingTokens;
        }

        private List<RagPromptTemplates.ContextEvidence> evidence() {
            return List.copyOf(evidence);
        }
    }
}
