package com.wxx.aidocumentagent.rag.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.rag.RagProperties;
import com.wxx.aidocumentagent.retrieval.RetrievedChunk;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * 将索引命中重新约束到当前知识库的持久化 document_chunk/document，再生成稳定 C#。
 * 索引内容只作为候选定位信息，绝不直接信任其文档名、页码或正文。
 */
public final class RagContextBuilder {

    private static final int MAX_OVERLAP_COMPARISON_CHARACTERS = 4_096;

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
        List<RagPromptTemplates.ContextCandidate> candidates = rehydrateCandidates(knowledgeBaseId, retrievedChunks);
        if (candidates.isEmpty()) {
            return new RagContext(RagPromptTemplates.userPrompt(question, List.of()), List.of(), 0);
        }

        int fixedPromptTokens = tokenCountEstimator.estimate(RagPromptTemplates.systemInstruction())
                + tokenCountEstimator.estimate(RagPromptTemplates.userPromptWithoutEvidence(question));
        int evidenceTokenBudget = Math.max(0, properties.inputTokenBudget() - fixedPromptTokens);
        if (evidenceTokenBudget <= 0) {
            return new RagContext(RagPromptTemplates.userPrompt(question, List.of()), List.of(), candidates.size());
        }

        Selection selection = new Selection(properties.getMaxContextCharacters(), evidenceTokenBudget);
        Set<Long> documentsRepresented = new HashSet<>();
        // 第一轮优先每个来源文档的最高排名 chunk，避免单一长文档垄断上下文。
        for (RagPromptTemplates.ContextCandidate candidate : candidates) {
            if (!documentsRepresented.contains(candidate.documentId()) && tryAdd(candidate, selection)) {
                documentsRepresented.add(candidate.documentId());
            }
        }
        // 第二轮按原始检索排名补齐，并对同一文档确有文本重叠的连续 chunk 去冗余。
        for (RagPromptTemplates.ContextCandidate candidate : candidates) {
            if (!selection.containsChunk(candidate.chunkId()) && !isRedundant(candidate, selection.evidence())) {
                tryAdd(candidate, selection);
            }
        }

        List<RagCitation> citations = selection.evidence().stream()
                .map(RagPromptTemplates.ContextEvidence::citation)
                .toList();
        return new RagContext(RagPromptTemplates.userPrompt(question, selection.evidence()), citations, candidates.size());
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
        String citationId = "C" + (selection.evidence().size() + 1);
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
            int distance = Math.abs(candidate.chunkIndex() - selectedCandidate.chunkIndex());
            if (distance == 0) {
                return true;
            }
            if (distance == 1 && overlapsAtBoundary(candidate.content(), selectedCandidate.content())) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsAtBoundary(String first, String second) {
        int threshold = properties.getRedundantOverlapCharacters();
        if (threshold == 0) {
            return true;
        }
        return suffixPrefixOverlap(first, second, threshold) >= threshold
                || suffixPrefixOverlap(second, first, threshold) >= threshold;
    }

    private int suffixPrefixOverlap(String first, String second, int minimum) {
        int maximum = Math.min(MAX_OVERLAP_COMPARISON_CHARACTERS, Math.min(first.length(), second.length()));
        for (int length = maximum; length >= minimum; length--) {
            if (first.regionMatches(first.length() - length, second, 0, length)) {
                return length;
            }
        }
        return 0;
    }

    private static boolean isChunkWithinKnowledgeBase(DocumentChunk chunk, long knowledgeBaseId, long documentId) {
        return chunk != null && chunk.getId() != null && chunk.getKnowledgeBaseId() != null
                && chunk.getDocumentId() != null && chunk.getKnowledgeBaseId() == knowledgeBaseId
                && chunk.getDocumentId() == documentId;
    }

    private static boolean isDocumentWithinKnowledgeBase(Document document, long knowledgeBaseId) {
        return document != null && document.getId() != null && document.getKnowledgeBaseId() != null
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

        private Selection(int remainingCharacters, int remainingTokens) {
            this.remainingCharacters = remainingCharacters;
            this.remainingTokens = remainingTokens;
        }

        private boolean containsChunk(long chunkId) {
            return evidence.stream().anyMatch(item -> item.citation().chunkId() == chunkId);
        }

        private void add(RagPromptTemplates.ContextEvidence item, int characterCost, int tokenCost) {
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
