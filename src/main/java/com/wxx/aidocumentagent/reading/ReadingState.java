package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wxx.aidocumentagent.agent.AgentCitation;
import com.wxx.aidocumentagent.agent.ClaimEvidenceValidator;

/** Durable stage artifacts. Unverified drafts never appear in public task/result DTOs. */
public final class ReadingState {
    public int schemaVersion = 1;
    public List<Goal> goals = new ArrayList<>();
    public List<Source> sources = new ArrayList<>();
    public List<Finding> findings = new ArrayList<>();
    public List<String> gaps = new ArrayList<>();
    public List<String> materialGaps = new ArrayList<>();
    public List<Stage> stages = new ArrayList<>();
    public Map<Long, Cursor> cursors = new LinkedHashMap<>();
    public int modelCalls;
    public int toolCalls;
    public int replans;
    public int sourceSequence;
    public int findingSequence;
    public boolean planned;
    public boolean comprehensive;
    public String parentGoal;
    public String pendingDraft;
    public List<String> pendingSourceIds = new ArrayList<>();
    public List<String> pendingReadSourceIds = new ArrayList<>();
    public Long pendingDocumentId;
    public Cursor pendingCursor;
    public String stopReason;
    public long promptTokens;
    public long completionTokens;

    public record DocumentRef(long id, String name, String fileSha256, String fingerprint, long chunks, List<String> limitations) {
        public DocumentRef { limitations = limitations == null ? List.of() : List.copyOf(limitations); }
        public DocumentRef(long id, String name, String fileSha256, String fingerprint, long chunks) {
            this(id, name, fileSha256, fingerprint, chunks, List.of());
        }
    }
    public record Cursor(int chunkIndex, int offset, long reviewedChunks) {
        public static Cursor start() { return new Cursor(0, 0, 0); }
    }
    public record Goal(String id, String description, String query, String engine, boolean covered) { }
    public record Support(String citationId, String quote) { }
    public record Finding(String id, String goalId, String kind, String text, List<Support> supports) {
        public Finding { supports = List.copyOf(supports); }
    }
    public record Source(String citationId, long documentId, String documentName, String documentVersion,
                         long chunkId, int chunkIndex, String contentHash, int offset, String text,
                         Integer pageFrom, Integer pageTo, String sectionTitle) {
        public ClaimEvidenceValidator.Visible visible(long knowledgeBaseId) {
            return new ClaimEvidenceValidator.Visible(new AgentCitation(knowledgeBaseId, citationId, documentId,
                    documentName, chunkId, pageFrom, pageTo, text), text, documentVersion);
        }
    }
    public record Stage(String engine, String operation, String status, String message, String at) { }

    public void stage(String engine, String operation, String status, String message) {
        stages.add(new Stage(engine, operation, status, message, Instant.now().toString()));
        if (stages.size() > 100) stages.removeFirst();
    }

    public List<Source> visibleSources() {
        List<Source> window = new ArrayList<>();
        int characters = 0;
        for (int i = sources.size() - 1; i >= 0 && window.size() < 8; i--) {
            var source = sources.get(i);
            if (characters + source.text().length() > 14_000) break;
            window.addFirst(source); characters += source.text().length();
        }
        return List.copyOf(window);
    }

    public Source register(Source candidate) {
        var existing = sources.stream().filter(s -> s.chunkId() == candidate.chunkId()
                && s.offset() == candidate.offset() && s.text().equals(candidate.text())
                && s.documentVersion().equals(candidate.documentVersion())).findFirst();
        if (existing.isPresent()) {
            sources.remove(existing.get()); sources.add(existing.get()); return existing.get();
        }
        if (sources.size() >= 96) {
            var used = findings.stream().flatMap(f -> f.supports().stream()).map(Support::citationId)
                    .collect(java.util.stream.Collectors.toSet());
            sources.removeIf(s -> !used.contains(s.citationId()));
        }
        if (sources.size() >= 96) throw new ReadingBudget.Exhausted("证据窗口已满，请缩小阅读目标");
        Source registered = new Source("C" + ++sourceSequence, candidate.documentId(), candidate.documentName(),
                candidate.documentVersion(), candidate.chunkId(), candidate.chunkIndex(), candidate.contentHash(),
                candidate.offset(), candidate.text(), candidate.pageFrom(), candidate.pageTo(), candidate.sectionTitle());
        sources.add(registered);
        return registered;
    }

    public String completion(List<DocumentRef> documents) {
        boolean coverage = !comprehensive || documents.stream().allMatch(d ->
                cursors.getOrDefault(d.id(), Cursor.start()).reviewedChunks() >= d.chunks());
        if (!findings.isEmpty() && coverage && !goals.isEmpty() && goals.stream().allMatch(Goal::covered)
                && gaps.isEmpty() && materialGaps.isEmpty()) return "FULL";
        return findings.isEmpty() ? "NONE" : "PARTIAL";
    }

    public void newRound() {
        modelCalls = 0; toolCalls = 0; replans = 0; stopReason = null;
        promptTokens = 0; completionTokens = 0;
    }

    public void invalidateChanged(List<DocumentRef> previous, List<DocumentRef> current) {
        var changed = new java.util.HashSet<Long>();
        for (var old : previous) if (current.stream().noneMatch(d -> d.id() == old.id()
                && d.fingerprint().equals(old.fingerprint()))) changed.add(old.id());
        var affected = sources.stream().filter(s -> changed.contains(s.documentId())).map(Source::citationId)
                .collect(java.util.stream.Collectors.toSet());
        findings.removeIf(f -> f.supports().stream().anyMatch(s -> affected.contains(s.citationId())));
        sources.removeIf(s -> changed.contains(s.documentId())); changed.forEach(cursors::remove);
        goals = goals.stream().map(g -> new Goal(g.id(), g.description(), g.query(), g.engine(), false))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        pendingDraft = null; pendingSourceIds = new ArrayList<>(); pendingReadSourceIds = new ArrayList<>();
        pendingDocumentId = null; pendingCursor = null; gaps = new ArrayList<>();
        stage("PLAN_EXECUTE_REFLECT", "source_refresh", "SUCCEEDED", "资料已更新，重新核验受影响内容，历史笔记仍保留");
    }
}
