package com.wxx.aidocumentagent.agent.react;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.AgentExecutionContext;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import com.wxx.aidocumentagent.agent.AgentConversationSupport;
import com.wxx.aidocumentagent.rag.application.RagConversationTurn;
import com.wxx.aidocumentagent.rag.application.EvidenceOverlap;
import com.wxx.aidocumentagent.rag.application.EvidenceRelevance;
import com.wxx.aidocumentagent.agent.ClaimEvidenceValidator;

/** 每次请求独占；只有已完成且按知识库复核的证据才能被后续工具引用。 */
public final class ToolExecutionContext {

    private final long knowledgeBaseId;
    private final AgentExecutionContext agentContext;
    private final Instant deadline;
    private final int maxEvidenceChunks;
    private final int maxEvidenceTokens;
    private final int maxRegisteredSources;
    private final Map<Long, EvidenceChunk> sourceLedger = new LinkedHashMap<>();
    private String goal = "";
    private String focus = "";
    private String searchFocus = "";
    private final java.util.Set<Long> presented = new java.util.HashSet<>();
    private final java.util.Set<Long> retained = new java.util.LinkedHashSet<>();
    private int evidenceTokens;
    private final Map<Long, AgentCitation> citations = new LinkedHashMap<>();
    private final Map<Long, EvidenceChunk> evidence = new LinkedHashMap<>();
    private final Map<Long, Integer> overlapPrefixLimits = new LinkedHashMap<>();
    private final Map<Long, Integer> overlapMaterialLengths = new LinkedHashMap<>();

    public ToolExecutionContext(long knowledgeBaseId, AgentExecutionContext agentContext,
                                Instant deadline, int maxEvidenceChunks) {
        this(knowledgeBaseId, agentContext, deadline, maxEvidenceChunks, 8_000);
    }

    public ToolExecutionContext(long knowledgeBaseId, AgentExecutionContext agentContext,
                                Instant deadline, int maxEvidenceChunks, int maxEvidenceTokens) {
        this(knowledgeBaseId, agentContext, deadline, maxEvidenceChunks, maxEvidenceTokens, maxEvidenceChunks);
    }

    public ToolExecutionContext(long knowledgeBaseId, AgentExecutionContext agentContext,
                                Instant deadline, int maxEvidenceChunks, int maxEvidenceTokens, int maxRegisteredSources) {
        if (knowledgeBaseId <= 0 || maxEvidenceChunks <= 0 || maxEvidenceTokens <= 0) {
            throw new IllegalArgumentException("工具上下文边界不合法");
        }
        this.knowledgeBaseId = knowledgeBaseId;
        this.agentContext = Objects.requireNonNull(agentContext);
        this.deadline = Objects.requireNonNull(deadline);
        this.maxEvidenceChunks = maxEvidenceChunks;
        this.maxEvidenceTokens = maxEvidenceTokens;
        if (maxRegisteredSources < maxEvidenceChunks || maxRegisteredSources > 200)
            throw new IllegalArgumentException("Invalid source ledger limit");
        this.maxRegisteredSources = maxRegisteredSources;
        if (agentContext.memory().knowledgeBaseId() != knowledgeBaseId) {
            throw new IllegalArgumentException("工具上下文知识库不匹配");
        }
    }

    public long knowledgeBaseId() { return knowledgeBaseId; }
    public boolean durable() { return agentContext.durableExecutionId() != null; }
    public void checkpointStep(AgentStepSummary summary) { agentContext.checkpointStep(summary); }
    public com.wxx.aidocumentagent.agent.AgentExecutionProbe.ToolAction beforeTool(
            String name, Map<String, String> summary) { return agentContext.beforeTool(name, summary); }
    public void observeToolResult(String name, Map<String, String> summary) {
        agentContext.observeToolResult(name, summary);
    }
    public boolean isAuthorizedChunk(long chunkId) { return maxRegisteredSources > maxEvidenceChunks ? presented.contains(chunkId) : citations.containsKey(chunkId); }
    public List<AgentCitation> citations() { return citations.values().stream().filter(citation -> evidence.containsKey(citation.chunkId())).toList(); }
    public List<AgentCitation> registeredCitations() { return List.copyOf(citations.values()); }
    public String goal() { return goal; }
    public void setGoal(String question) { goal = Objects.requireNonNull(question); }
    public void focusOn(List<String> gaps) {
        focus = String.join(" ", gaps); selectWindow();
        for (var chunk : evidence.values()) {
            agentContext.observeEvidence(citations.get(chunk.chunkId()), chunk.text());
            presented.add(chunk.chunkId());
        }
    }
    public void diagnostic(String stage, Map<String, Object> details) { agentContext.diagnostic(stage, details); }
    public boolean fitsEvidenceInput(String text) { return EvidenceTokenBudget.tokens(text) <= maxEvidenceTokens; }
    public void retainBoundSources(List<com.wxx.aidocumentagent.agent.ClaimEvidenceBinding> bindings) {
        // Retain provenance-validated subgoal inputs; this does not certify their semantic correctness.
        retained.clear();
        for (var binding : bindings) for (var source : binding.sources()) {
            if (retained.size() >= Math.min(4, maxEvidenceChunks / 2)) return;
            if (evidence.containsKey(source.citation().chunkId())) retained.add(source.citation().chunkId());
            break;
        }
    }
    public List<ClaimEvidenceValidator.Visible> visible() {
        return citations().stream().map(citation -> new ClaimEvidenceValidator.Visible(citation, evidence(citation.chunkId()).text(),
                evidence(citation.chunkId()).sourceContentSha256())).toList();
    }
    public List<Map<String, Object>> fingerprints() {
        return visible().stream().map(source -> Map.<String, Object>of("citationId", source.citation().citationId(),
                "kind", "SOURCE_PREFIX", "chunkId", source.citation().chunkId(), "characters", source.text().length(),
                "sha256", ClaimEvidenceValidator.sha256(source.text()))).toList();
    }
    public String passages() {
        StringBuilder result = new StringBuilder();
        for (var citation : citations()) result.append(header(citation)).append(evidence(citation.chunkId()).text()).append('\n');
        return result.toString();
    }
    private String header(AgentCitation c) {
        return "[" + c.citationId() + "] documentId=" + c.documentId() + " chunkId=" + c.chunkId() + " " + c.documentName() + ": ";
    }
    public List<RagConversationTurn> history() { return AgentConversationSupport.history(agentContext.memory().messages()); }
    public List<EvidenceChunk> evidence() { return List.copyOf(evidence.values()); }
    public EvidenceChunk evidence(long chunkId) { return evidence.get(chunkId); }
    public int remainingEvidenceTokens() { return maxEvidenceTokens - evidenceTokens; }
    public int remainingEvidenceSlots() { return maxEvidenceChunks - evidence.size(); }

    public Duration remainingTime() {
        Duration local = Duration.between(Instant.now(), deadline);
        Duration outer = agentContext.remainingTime();
        if (local.isNegative()) return Duration.ZERO;
        return local.compareTo(outer) < 0 ? local : outer;
    }

    public void checkActive() {
        agentContext.checkActive();
        if (remainingTime().isZero()) {
            throw new IllegalStateException("Agent执行截止时间已到");
        }
    }

    /** 只在工具及时返回后由循环线程调用，超时后台任务无法授予 chunk 权限。 */
    public Map<Long, AgentCitation> registerEvidence(List<EvidenceChunk> chunks) {
        checkActive();
        if (maxRegisteredSources > maxEvidenceChunks) return registerWindow(chunks);
        Map<Long, String> before = new LinkedHashMap<>();
        evidence.forEach((id, chunk) -> before.put(id, chunk.text()));
        Map<Long, AgentCitation> added = new LinkedHashMap<>();
        for (EvidenceChunk chunk : chunks) {
            if (chunk.knowledgeBaseId() != knowledgeBaseId || chunk.chunkId() <= 0 || chunk.documentId() <= 0
                    || chunk.documentName() == null || chunk.text() == null || chunk.text().isBlank()) {
                continue;
            }
            AgentCitation existing = citations.get(chunk.chunkId());
            if (existing != null) {
                if (existing.documentId() != chunk.documentId() || !existing.documentName().equals(chunk.documentName())
                        || !Objects.equals(existing.pageFrom(), chunk.pageFrom())
                        || !Objects.equals(existing.pageTo(), chunk.pageTo())) continue;
                EvidenceChunk previous = evidence.get(chunk.chunkId());
                if (!(previous.text().startsWith(chunk.text()) || chunk.text().startsWith(previous.text()))) continue;
                int oldTokens = EvidenceTokenBudget.tokens(previous.text());
                String material = chunk.text();
                if (overlapPrefixLimits.containsKey(chunk.chunkId())
                        && material.length() <= overlapMaterialLengths.get(chunk.chunkId())) {
                    material = material.substring(0, Math.min(material.length(), overlapPrefixLimits.get(chunk.chunkId())));
                }
                String expanded = EvidenceTokenBudget.prefix(material, remainingEvidenceTokens() + oldTokens);
                if (expanded.length() > previous.text().length()) {
                    evidence.put(chunk.chunkId(), withText(chunk, expanded));
                    evidenceTokens += EvidenceTokenBudget.tokens(expanded) - oldTokens;
                }
                added.put(chunk.chunkId(), existing);
                continue;
            }
            if (citations.size() >= maxEvidenceChunks) continue;
            String visible = EvidenceTokenBudget.prefix(chunk.text(), remainingEvidenceTokens());
            if (visible.isBlank() || visible.length() < Math.min(64, chunk.text().length())) continue;
            String quote = truncate(visible, 500);
            AgentCitation citation = new AgentCitation(knowledgeBaseId, "C" + (citations.size() + 1),
                    chunk.documentId(), chunk.documentName(), chunk.chunkId(), chunk.pageFrom(), chunk.pageTo(), quote);
            citations.put(chunk.chunkId(), citation);
            evidence.put(chunk.chunkId(), withText(chunk, visible));
            evidenceTokens += EvidenceTokenBudget.tokens(visible);
            added.put(chunk.chunkId(), citation);
        }
        var selected = added.keySet().stream().map(evidence::get).toList();
        var prefixes = EvidenceOverlap.prefixes(selected.stream().map(chunk -> new EvidenceOverlap.Part(
                chunk.chunkId(), chunk.documentId(), chunk.text(), citations.get(chunk.chunkId()).quote().length())).toList());
        for (var chunk : selected) {
            String prefix = prefixes.get(chunk.chunkId());
            boolean fullMaterial = chunks.stream().anyMatch(raw -> raw.knowledgeBaseId() == knowledgeBaseId
                    && raw.chunkId() == chunk.chunkId() && chunk.text().equals(raw.text()));
            if (!before.containsKey(chunk.chunkId()) && fullMaterial && prefix.length() < chunk.text().length()) {
                evidenceTokens += EvidenceTokenBudget.tokens(prefix) - EvidenceTokenBudget.tokens(chunk.text());
                evidence.put(chunk.chunkId(), withText(chunk, prefix));
                overlapPrefixLimits.put(chunk.chunkId(), prefix.length());
                overlapMaterialLengths.put(chunk.chunkId(), chunk.text().length());
            }
        }
        for (Long id : added.keySet()) {
            String visible = evidence.get(id).text();
            if (!visible.equals(before.get(id))) agentContext.observeEvidence(citations.get(id), visible);
        }
        return Map.copyOf(added);
    }

    public Map<Long, AgentCitation> registerEvidence(EvidenceResult result) {
        if (result.selectionQuery() != null) searchFocus = result.selectionQuery();
        return registerEvidence(result.chunks());
    }

    private Map<Long, AgentCitation> registerWindow(List<EvidenceChunk> chunks) {
        Map<Long, String> before = new LinkedHashMap<>();
        java.util.Set<Long> acceptedIds = new java.util.HashSet<>();
        evidence.forEach((id, chunk) -> before.put(id, chunk.text()));
        for (EvidenceChunk chunk : chunks) {
            if (chunk.knowledgeBaseId() != knowledgeBaseId || chunk.chunkId() <= 0 || chunk.documentId() <= 0
                    || chunk.documentName() == null || chunk.text() == null || chunk.text().isBlank()) continue;
            var old = sourceLedger.get(chunk.chunkId());
            if (old != null) {
                if (old.documentId() != chunk.documentId() || !old.documentName().equals(chunk.documentName())
                        || !Objects.equals(old.pageFrom(), chunk.pageFrom()) || !Objects.equals(old.pageTo(), chunk.pageTo())
                        || old.sourceContentSha256() != null && !old.sourceContentSha256().equals(chunk.sourceContentSha256())
                        || !(old.text().startsWith(chunk.text()) || chunk.text().startsWith(old.text()))) continue;
                acceptedIds.add(chunk.chunkId());
                if (old.text().length() >= chunk.text().length()) continue;
            }
            else {
                if (citations.size() >= maxRegisteredSources) continue;
                citations.put(chunk.chunkId(), new AgentCitation(knowledgeBaseId, "C" + (citations.size() + 1), chunk.documentId(),
                        chunk.documentName(), chunk.chunkId(), chunk.pageFrom(), chunk.pageTo(), truncate(chunk.text(), 500)));
                acceptedIds.add(chunk.chunkId());
            }
            // Ledger has its own count and per-source hard bounds, independent of the active prompt window.
            sourceLedger.put(chunk.chunkId(), withText(chunk, EvidenceTokenBudget.prefix(truncate(chunk.text(), 8_000), maxEvidenceTokens)));
        }
        selectWindow();
        Map<Long, AgentCitation> selected = new LinkedHashMap<>();
        for (var item : evidence.values()) {
            var citation = citations.get(item.chunkId());
            if (acceptedIds.contains(item.chunkId())) selected.put(item.chunkId(), citation);
            if (!item.text().equals(before.get(item.chunkId()))) agentContext.observeEvidence(citation, item.text());
            presented.add(item.chunkId());
        }
        diagnostic("evidence_window", Map.of("registered", citations.size(), "registryLimit", maxRegisteredSources,
                "tokens", evidenceTokens, "tokenLimit", maxEvidenceTokens, "activeChunkIds", List.copyOf(evidence.keySet()),
                "evictedChunkIds", before.keySet().stream().filter(id -> !evidence.containsKey(id)).toList()));
        return Map.copyOf(selected);
    }

    private void selectWindow() {
        if (sourceLedger.isEmpty()) return;
        List<EvidenceChunk> ordered = sourceLedger.values().stream().sorted(java.util.Comparator.comparingDouble(
                (EvidenceChunk item) -> EvidenceRelevance.score(goal, item.text())
                        + .7 * EvidenceRelevance.score(focus, item.text())
                        + .5 * EvidenceRelevance.score(searchFocus, item.text())
                        + 2 * EvidenceRelevance.documentAffinity(goal, item.documentName())).reversed()).toList();
        evidence.clear();
        evidenceTokens = 0;
        for (Long id : retained) if (sourceLedger.containsKey(id)) admit(sourceLedger.get(id), ordered.size());
        java.util.Set<Long> represented = new java.util.HashSet<>();
        evidence.values().forEach(item -> represented.add(item.documentId()));
        for (var item : ordered) if (!represented.contains(item.documentId()) && admit(item, ordered.size())) represented.add(item.documentId());
        for (var item : ordered) if (!evidence.containsKey(item.chunkId())) admit(item, ordered.size());
        var prefixes = EvidenceOverlap.prefixes(evidence.values().stream().map(item -> new EvidenceOverlap.Part(
                item.chunkId(), item.documentId(), item.text(), citations.get(item.chunkId()).quote().length())).toList());
        evidence.replaceAll((id, item) -> withText(item, prefixes.get(id)));
        evidenceTokens = EvidenceTokenBudget.tokens(passages());
        while (evidenceTokens > maxEvidenceTokens && !evidence.isEmpty()) {
            Long last = new java.util.ArrayList<>(evidence.keySet()).getLast();
            evidence.remove(last);
            evidenceTokens = EvidenceTokenBudget.tokens(passages());
        }
    }

    private boolean admit(EvidenceChunk chunk, int candidates) {
        if (evidence.size() >= maxEvidenceChunks || remainingEvidenceTokens() <= 0) return false;
        int perSource = Math.max(256, maxEvidenceTokens / Math.max(1, Math.min(6, candidates)));
        // Per-source fair share prevents one early passage from exhausting the entire active window.
        var c = citations.get(chunk.chunkId());
        int overhead = EvidenceTokenBudget.tokens(header(c) + "\n");
        String text = EvidenceTokenBudget.prefix(chunk.text(), Math.min(perSource, remainingEvidenceTokens()) - overhead);
        if (text.length() < Math.min(64, chunk.text().length())) return false;
        if (!text.startsWith(citations.get(chunk.chunkId()).quote())) return false;
        evidence.put(chunk.chunkId(), withText(chunk, text));
        evidenceTokens += EvidenceTokenBudget.tokens(text) + overhead;
        return true;
    }

    private EvidenceChunk withText(EvidenceChunk source, String text) {
        return new EvidenceChunk(source.knowledgeBaseId(), source.chunkId(), source.documentId(), source.documentName(),
                source.pageFrom(), source.pageTo(), text, source.sourceContentSha256());
    }

    private String truncate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        return count <= maxCodePoints ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
