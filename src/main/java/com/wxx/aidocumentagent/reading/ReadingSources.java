package com.wxx.aidocumentagent.reading;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunk;
import com.wxx.aidocumentagent.chunking.infrastructure.persistence.DocumentChunkRepository;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.Document;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import com.wxx.aidocumentagent.retrieval.HybridRetriever;
import com.wxx.aidocumentagent.retrieval.RetrievalQuery;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** All source access requires an explicit nonempty document allowlist. */
public class ReadingSources {
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final HybridRetriever retrieval;
    private final boolean rerank;
    public ReadingSources(DocumentRepository documents, DocumentChunkRepository chunks, HybridRetriever retrieval) {
        this(documents, chunks, retrieval, false);
    }
    public ReadingSources(DocumentRepository documents, DocumentChunkRepository chunks, HybridRetriever retrieval, boolean rerank) {
        this.documents = documents; this.chunks = chunks; this.retrieval = retrieval; this.rerank = rerank;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, timeout = 10, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<DocumentRef> snapshot(long kb, Set<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException(ReadingError.INVALID_SCOPE);
        var selected = documents.findByKnowledgeBaseIdAndIdIn(kb, ids);
        if (selected.size() != ids.size() || selected.stream().anyMatch(d -> d.getStatus() != DocumentStatus.READY))
            throw new BusinessException(ReadingError.INVALID_SCOPE);
        return selected.stream().sorted(java.util.Comparator.comparing(Document::getId)).map(d -> fingerprint(kb, d)).toList();
    }

    private DocumentRef fingerprint(long kb, Document doc) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(doc.getSha256().getBytes(StandardCharsets.UTF_8));
            int page = 0;
            long count = 0;
            org.springframework.data.domain.Page<DocumentChunkRepository.VersionSummary> rows;
            do {
                rows = chunks.findVersionsByKnowledgeBaseIdAndDocumentId(kb, doc.getId(),
                        PageRequest.of(page++, 2048, Sort.by("chunkIndex")));
                for (var row : rows) {
                    digest.update((row.getId() + ":" + row.getChunkIndex() + ":" + row.getContentHash() + ":"
                            + row.getPageFrom() + ":" + row.getPageTo() + ":" + row.getSectionTitle() + ":"
                            + row.getUpdatedAt() + "\n").getBytes(StandardCharsets.UTF_8));
                    count++;
                }
            } while (rows.hasNext());
            if (count == 0) throw new BusinessException(ReadingError.INVALID_SCOPE);
            return new DocumentRef(doc.getId(), doc.getOriginalName(), doc.getSha256(),
                    HexFormat.of().formatHex(digest.digest()), count, limitations(doc));
        }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Submission only validates ready documents; expensive fingerprints are prepared by the queued worker. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true, timeout = 5)
    public List<DocumentRef> select(long kb, Set<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException(ReadingError.INVALID_SCOPE);
        var selected = documents.findByKnowledgeBaseIdAndIdIn(kb, ids);
        if (selected.size() != ids.size() || selected.stream().anyMatch(d -> d.getStatus() != DocumentStatus.READY))
            throw new BusinessException(ReadingError.INVALID_SCOPE);
        return selected.stream().sorted(java.util.Comparator.comparing(Document::getId))
                .map(d -> new DocumentRef(d.getId(), d.getOriginalName(), d.getSha256(), d.getSha256(), 0, limitations(d))).toList();
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Source> search(long kb, List<DocumentRef> scope, String query) {
        if (query == null || query.isBlank() || query.length() > 400) throw new BusinessException(ReadingError.INVALID_REQUEST);
        Set<Long> ids = scopeIds(scope);
        checkVersions(kb, scope);
        var hits = retrieval.retrieve(new RetrievalQuery(kb, query, 16, 16, 8, ids), rerank).chunks();
        if (hits.isEmpty()) return List.of();
        var persisted = chunks.findByKnowledgeBaseIdAndIdIn(kb, hits.stream().map(h -> h.chunkId()).toList())
                .stream().collect(Collectors.toMap(DocumentChunk::getId, d -> d));
        List<Source> result = new ArrayList<>();
        for (var hit : hits) {
            var row = persisted.get(hit.chunkId());
            if (row != null && ids.contains(row.getDocumentId()) && row.getContent().equals(hit.content())) {
                var ref = requireDocument(scope, row.getDocumentId());
                result.add(source(ref, row.getId(), row.getChunkIndex(), row.getContentHash(), 0,
                        bounded(row.getContent(), 2_800), row.getPageFrom(), row.getPageTo(), row.getSectionTitle()));
            }
        }
        return List.copyOf(result);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Source> context(long kb, List<DocumentRef> scope, ReadingState state, long chunkId) {
        checkVersions(kb, scope);
        var authorized = state.sources.stream().filter(s -> s.chunkId() == chunkId).findFirst()
                .orElseThrow(() -> new BusinessException(ReadingError.INVALID_SCOPE));
        var doc = requireDocument(scope, authorized.documentId());
        return chunks.findByKnowledgeBaseIdAndDocumentIdAndChunkIndexBetweenOrderByChunkIndexAsc(kb, doc.id(),
                        Math.max(0, authorized.chunkIndex() - 1), authorized.chunkIndex() + 1).stream()
                .map(row -> source(doc, row.getId(), row.getChunkIndex(), row.getContentHash(), 0,
                        bounded(row.getContent(), 2_800), row.getPageFrom(), row.getPageTo(), row.getSectionTitle())).toList();
    }

    public record ReadPage(List<Source> sources, Cursor next) { }
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReadPage read(long kb, List<DocumentRef> scope, long documentId, Cursor cursor) {
        checkVersions(kb, scope);
        var doc = requireDocument(scope, documentId);
        var page = chunks.findAllProjectedByKnowledgeBaseIdAndDocumentIdAndChunkIndexGreaterThanEqual(kb, documentId,
                cursor.chunkIndex(), PageRequest.of(0, 4, Sort.by("chunkIndex")));
        List<Source> sources = new ArrayList<>();
        Cursor next = cursor;
        int characters = 0;
        for (var row : page) {
            int offset = row.getChunkIndex() == cursor.chunkIndex() ? cursor.offset() : 0;
            if (offset < 0 || offset >= row.getContent().length()) throw new BusinessException(ReadingError.SOURCE_CHANGED);
            String text = bounded(row.getContent().substring(offset), 4_200);
            if (characters + text.length() > 12_000) break;
            sources.add(source(doc, row.getId(), row.getChunkIndex(), row.getContentHash(), offset,
                    text, row.getPageFrom(), row.getPageTo(), row.getSectionTitle()));
            characters += text.length();
            if (offset + text.length() == row.getContent().length())
                next = new Cursor(row.getChunkIndex() + 1, 0, next.reviewedChunks() + 1);
            else { next = new Cursor(row.getChunkIndex(), offset + text.length(), next.reviewedChunks()); break; }
        }
        if (sources.isEmpty() && cursor.reviewedChunks() < doc.chunks()) throw new BusinessException(ReadingError.SOURCE_CHANGED);
        return new ReadPage(List.copyOf(sources), next);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void verify(long kb, List<DocumentRef> scope, ReadingState state) {
        checkVersions(kb, scope);
        if (state.sources.isEmpty()) return;
        var rows = chunks.findByKnowledgeBaseIdAndIdIn(kb, state.sources.stream().map(Source::chunkId).distinct().toList())
                .stream().collect(Collectors.toMap(DocumentChunk::getId, d -> d));
        for (var s : state.sources) {
            var row = rows.get(s.chunkId());
            var doc = requireDocument(scope, s.documentId());
            if (row == null || row.getDocumentId() != s.documentId() || !s.documentVersion().equals(doc.fingerprint())
                    || !row.getContentHash().equals(s.contentHash()) || s.offset() < 0
                    || s.offset() + s.text().length() > row.getContent().length()
                    || !row.getContent().regionMatches(s.offset(), s.text(), 0, s.text().length()))
                throw new BusinessException(ReadingError.SOURCE_CHANGED);
        }
    }

    public static Set<Long> scopeIds(List<DocumentRef> scope) {
        if (scope == null || scope.isEmpty()) throw new BusinessException(ReadingError.INVALID_SCOPE);
        return scope.stream().map(DocumentRef::id).collect(Collectors.toUnmodifiableSet());
    }
    private void checkVersions(long kb, List<DocumentRef> scope) {
        if (!snapshot(kb, scopeIds(scope)).equals(scope)) throw new BusinessException(ReadingError.SOURCE_CHANGED);
    }
    public static DocumentRef requireDocument(List<DocumentRef> scope, long id) {
        return scope.stream().filter(d -> d.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ReadingError.INVALID_SCOPE));
    }
    private Source source(DocumentRef doc, long id, int index, String hash, int offset, String text,
                          Integer from, Integer to, String title) {
        return new Source(null, doc.id(), doc.name(), doc.fingerprint(), id, index, hash, offset, text, from, to, title);
    }
    private static String bounded(String value, int max) {
        int end = Math.min(max, value.length());
        if (end < value.length() && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
    private static List<String> limitations(Document doc) {
        return Set.of("pdf", "docx").contains(doc.getExtension() == null ? "" : doc.getExtension().toLowerCase(java.util.Locale.ROOT))
                ? List.of("仅核验已抽取的文本；图像、图表、复杂公式及扫描识别的完整性尚未核验") : List.of();
    }
}
