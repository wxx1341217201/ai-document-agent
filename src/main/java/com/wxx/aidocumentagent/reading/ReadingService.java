package com.wxx.aidocumentagent.reading;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.wxx.aidocumentagent.agent.ClaimEvidenceValidator;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import tools.jackson.databind.ObjectMapper;

public final class ReadingService {
    private final ReadingStore store;
    private final ReadingSources sources;
    private final ObjectMapper mapper;
    public ReadingService(ReadingStore store, ReadingSources sources, ObjectMapper mapper) {
        this.store = store; this.sources = sources; this.mapper = mapper;
    }

    public ReadingApi.Task create(String requestKey, ReadingApi.Create request) {
        if (request.goal() == null || request.goal().isBlank() || request.goal().length() > 2000
                || request.documentIds() == null || request.documentIds().isEmpty() || request.documentIds().size() > 20)
            throw new BusinessException(ReadingError.INVALID_REQUEST);
        Set<Long> ids = new HashSet<>(request.documentIds());
        if (ids.size() != request.documentIds().size() || ids.contains(null)) throw new BusinessException(ReadingError.INVALID_SCOPE);
        String key = requestKey == null ? UUID.randomUUID().toString() : requestKey;
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) throw new BusinessException(ReadingError.INVALID_REQUEST);
        var scope = sources.select(request.knowledgeBaseId(), ids);
        var state = new ReadingState();
        state.comprehensive = request.comprehensive() || ReadingCoordinator.comprehensive(request.goal(), scope.size());
        state.goals.add(new Goal("G0", request.goal().strip(), request.goal().strip().substring(0, Math.min(400, request.goal().strip().length())), "RETRIEVAL_FIRST", false));
        String session = null;
        if (request.parentTaskId() != null) {
            var parent = store.inspect(request.parentTaskId(), request.knowledgeBaseId());
            if (!ReadingSources.scopeIds(parent.scope()).equals(ids)) throw new BusinessException(ReadingError.INVALID_SCOPE);
            session = store.get(parent.id(), parent.knowledgeBaseId()).sessionId(); state.parentGoal = parent.goal();
            scope = parent.scope();
            state.sources = new ArrayList<>(parent.state().sources); state.sourceSequence = parent.state().sourceSequence;
        }
        String hash = ClaimEvidenceValidator.sha256(mapper.writeValueAsString(List.of(request.goal().strip(), ids.stream().sorted().toList(),
                state.comprehensive, request.parentTaskId() == null ? "" : request.parentTaskId())));
        return store.create(key, hash, request, scope, state, session);
    }

    public ReadingApi.Note note(String id, long kb) {
        var note = store.note(id, kb);
        String status = "PENDING";
        if (!note.sources().isEmpty()) {
            try {
                var ids = note.sources().stream().map(Source::documentId).collect(java.util.stream.Collectors.toSet());
                var current = sources.snapshot(kb, ids).stream().collect(java.util.stream.Collectors.toMap(DocumentRef::id, DocumentRef::fingerprint));
                status = note.sources().stream().allMatch(s -> s.documentVersion().equals(current.get(s.documentId()))) ? "CURRENT" : "CHANGED";
            }
            catch (RuntimeException unavailable) { status = "UNAVAILABLE"; }
        }
        return new ReadingApi.Note(note.id(), note.taskId(), note.content(), note.version(), note.adoptedResultId(),
                note.pendingResultId(), note.sources(), status, note.updatedAt());
    }

    public ReadingApi.Task expand(String id, long kb, ReadingApi.Expand request) {
        var ids = Set.copyOf(request.documentIds());
        if (ids.size() != request.documentIds().size()) throw new BusinessException(ReadingError.INVALID_SCOPE);
        var previous = store.inspect(id, kb);
        if (!ids.containsAll(ReadingSources.scopeIds(previous.scope())) || ids.size() <= previous.scope().size())
            throw new BusinessException(ReadingError.INVALID_SCOPE);
        var selected = sources.select(kb, ids);
        var scope = selected.stream().map(d -> previous.scope().stream().filter(old -> old.id() == d.id())
                .findFirst().orElse(d)).toList();
        return store.expand(id, kb, request.expectedVersion(), scope);
    }

    public ReadingApi.Task refresh(String id, long kb, ReadingApi.Action request) {
        var previous = store.inspect(id, kb);
        var current = sources.snapshot(kb, ReadingSources.scopeIds(previous.scope()));
        return store.refresh(id, kb, request.expectedVersion(), request.expectedRoundNo(), current);
    }
}
