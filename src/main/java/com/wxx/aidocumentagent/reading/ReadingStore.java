package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.reading.ReadingState.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Short transactions only: no model or retrieval requests while holding database locks. */
public class ReadingStore {
    private final ReadingTaskRepository tasks;
    private final ReadingResultRepository results;
    private final ReadingNoteRepository notes;
    private final ReadingNoteRevisionRepository revisions;
    private final ReadingDispatchLockRepository dispatch;
    private final ObjectMapper mapper;
    public ReadingStore(ReadingTaskRepository tasks, ReadingResultRepository results, ReadingNoteRepository notes,
                        ReadingNoteRevisionRepository revisions, ReadingDispatchLockRepository dispatch, ObjectMapper mapper) {
        this.tasks = tasks; this.results = results; this.notes = notes; this.revisions = revisions;
        this.dispatch = dispatch; this.mapper = mapper;
    }

    public record Run(String id, long knowledgeBaseId, String goal, int roundNo, String token,
                       Instant deadline, List<DocumentRef> scope, ReadingState state) { }

    @Transactional
    public ReadingApi.Task create(String key, String hash, ReadingApi.Create request, List<DocumentRef> scope,
                                   ReadingState state, String sessionId) {
        dispatch.acquire().orElseThrow();
        var existing = tasks.findByKnowledgeBaseIdAndRequestKey(request.knowledgeBaseId(), key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash.equals(hash)) throw new BusinessException(ReadingError.CONFLICT);
            return view(existing.get());
        }
        var task = new ReadingTask();
        task.id = UUID.randomUUID().toString(); task.knowledgeBaseId = request.knowledgeBaseId();
        task.sessionId = sessionId == null ? UUID.randomUUID().toString() : sessionId;
        task.parentTaskId = request.parentTaskId(); task.goal = request.goal().strip();
        task.requestKey = key; task.requestHash = hash; task.status = "PENDING";
        task.scopeJson = json(new ReadingApi.Scope(scope)); task.checkpointJson = json(state);
        tasks.saveAndFlush(task);
        var note = new ReadingNote(); note.id = UUID.randomUUID().toString(); note.taskId = task.id;
        note.sourcesJson = json(new ReadingApi.NoteSources(List.of()));
        notes.saveAndFlush(note);
        return view(task);
    }

    @Transactional(readOnly = true)
    public Run inspect(String id, long kb) {
        var task = require(id, kb); return run(task);
    }
    @Transactional(readOnly = true)
    public ReadingApi.Task get(String id, long kb) { return view(require(id, kb)); }

    @Transactional(readOnly = true)
    public ReadingApi.Page<ReadingApi.Summary> list(long kb, int page, int size) {
        return list(kb, page, size, null);
    }
    @Transactional(readOnly = true)
    public ReadingApi.Page<ReadingApi.Summary> list(long kb, int page, int size, String sessionId) {
        var paging = PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        var found = sessionId == null ? tasks.findAllProjectedByKnowledgeBaseId(kb, paging)
                : tasks.findAllProjectedByKnowledgeBaseIdAndSessionId(kb, sessionId, paging);
        return new ReadingApi.Page<>(found.stream().map(t -> new ReadingApi.Summary(t.getId(), t.getKnowledgeBaseId(),
                t.getSessionId(), t.getGoal(), t.getStatus(), t.getCompletion(), t.getRoundNo(), t.getLatestResultId(),
                t.getCreatedAt(), t.getUpdatedAt())).toList(), page, size, found.getTotalElements(), found.getTotalPages());
    }

    @Transactional
    public Run claim() {
        dispatch.acquire().orElseThrow();
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); tasks.interruptExpired(now);
        if (tasks.existsByStatus("RUNNING")) return null;
        var next = tasks.findFirstByStatusOrderByCreatedAtAscIdAsc("PENDING");
        if (next.isEmpty()) return null;
        var task = next.get(); task.status = "RUNNING"; task.runToken = UUID.randomUUID().toString();
        if (task.deadlineAt == null) task.deadlineAt = now.plus(ReadingBudget.ROUND);
        task.leaseUntil = task.deadlineAt.isAfter(now) ? task.deadlineAt.plusSeconds(45) : now.plusSeconds(45);
        task.errorMessage = null; tasks.saveAndFlush(task);
        return run(task);
    }

    @Transactional
    public void checkpoint(Run run, ReadingState state) {
        var task = owned(run);
        task.checkpointJson = json(state);
        if (task.checkpointJson.toString().length() > 900_000) throw new ReadingBudget.Exhausted("阶段产物超过本轮存储上限");
        tasks.saveAndFlush(task);
    }

    @Transactional
    public Run refreshScope(Run run, List<DocumentRef> scope, ReadingState state) {
        var task = owned(run);
        task.scopeJson = json(new ReadingApi.Scope(scope));
        task.checkpointJson = json(state); tasks.saveAndFlush(task);
        return new Run(run.id(), run.knowledgeBaseId(), run.goal(), run.roundNo(), run.token(), run.deadline(), scope, state);
    }

    @Transactional
    public void finish(Run run, ReadingState state, boolean interrupted) {
        var task = owned(run);
        publish(task, state);
        task.checkpointJson = json(state);
        task.status = interrupted ? "INTERRUPTED" : "SUCCEEDED";
        task.errorMessage = interrupted ? state.stopReason : null;
        task.runToken = null; task.leaseUntil = null;
        tasks.saveAndFlush(task);
    }

    @Transactional
    public ReadingApi.Task action(String id, long kb, long expectedVersion, int expectedRoundNo, String action) {
        var task = tasks.lock(id, kb).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
        if (task.roundNo != expectedRoundNo || !action.equals("cancel") && task.version != expectedVersion)
            throw new BusinessException(ReadingError.CONFLICT);
        var state = state(task);
        switch (action) {
            case "continue" -> {
                if (!List.of("SUCCEEDED", "INTERRUPTED", "CANCELLED").contains(task.status)
                        || task.completion.equals("FULL")) throw new BusinessException(ReadingError.CONFLICT);
                state.newRound(); task.roundNo++; task.deadlineAt = null; task.status = "PENDING";
            }
            case "resume" -> {
                if (!task.status.equals("INTERRUPTED")) throw new BusinessException(ReadingError.CONFLICT);
                state.stopReason = null; task.status = "PENDING";
            }
            case "cancel" -> {
                if (!List.of("RUNNING", "PENDING").contains(task.status)) throw new BusinessException(ReadingError.CONFLICT);
                state.stopReason = "用户已取消本轮执行";
                publish(task, state); task.status = "CANCELLED";
            }
            default -> throw new BusinessException(ReadingError.INVALID_REQUEST);
        }
        task.runToken = null; task.leaseUntil = null; task.errorMessage = null;
        task.checkpointJson = json(state); tasks.saveAndFlush(task);
        return view(task);
    }

    @Transactional(readOnly = true)
    public ReadingApi.Result result(String id, long kb, String resultId) {
        require(id, kb);
        return mapper.treeToValue(results.findByIdAndTaskId(resultId, id)
                .orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND)).payloadJson, ReadingApi.Result.class);
    }
    @Transactional(readOnly = true)
    public ReadingApi.Page<ReadingApi.ResultSummary> resultHistory(String id, long kb, int page, int size) {
        require(id, kb);
        var found = results.findAllProjectedByTaskId(id, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new ReadingApi.Page<>(found.stream().map(r -> new ReadingApi.ResultSummary(r.getId(), r.getRoundNo(),
                r.getCompletion(), r.getCreatedAt())).toList(), page, size, found.getTotalElements(), found.getTotalPages());
    }
    @Transactional(readOnly = true)
    public ReadingApi.Note note(String id, long kb) {
        var task = require(id, kb);
        var note = notes.findByTaskId(id).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
        return noteView(task, note);
    }
    @Transactional
    public ReadingApi.Note edit(String id, long kb, ReadingApi.Edit request) {
        var task = require(id, kb);
        var note = notes.lock(id).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
        if (note.version != request.expectedVersion()) throw new BusinessException(ReadingError.CONFLICT);
        if (request.sourceResultId() != null && results.findByIdAndTaskId(request.sourceResultId(), id).isEmpty())
            throw new BusinessException(ReadingError.NOT_FOUND);
        archive(note); note.content = request.content();
        var ledger = new java.util.LinkedHashMap<String, Source>();
        mapper.treeToValue(note.sourcesJson, ReadingApi.NoteSources.class).sources().forEach(s -> ledger.put(s.citationId(), s));
        if (request.sourceResultId() != null) {
            note.adoptedResultId = request.sourceResultId();
            result(id, kb, request.sourceResultId()).sources().forEach(s -> ledger.put(s.citationId(), s));
        }
        ledger.values().removeIf(s -> !note.content.contains("[" + s.citationId() + "]"));
        if (ledger.size() > 96) throw new BusinessException(ReadingError.INVALID_REQUEST);
        note.sourcesJson = json(new ReadingApi.NoteSources(List.copyOf(ledger.values())));
        notes.saveAndFlush(note); return noteView(task, note);
    }
    @Transactional(readOnly = true)
    public ReadingApi.Page<ReadingApi.RevisionSummary> noteHistory(String id, long kb, int page, int size) {
        require(id, kb); var note = notes.findByTaskId(id).orElseThrow();
        var found = revisions.findAllProjectedByNoteId(note.id, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "revision")));
        return new ReadingApi.Page<>(found.stream().map(r -> new ReadingApi.RevisionSummary(r.getId(), r.getRevision(),
                r.getSourceResultId(), r.getCreatedAt())).toList(), page, size, found.getTotalElements(), found.getTotalPages());
    }
    @Transactional(readOnly = true)
    public ReadingApi.Revision revision(String id, long kb, String revisionId) {
        require(id, kb); var note = notes.findByTaskId(id).orElseThrow();
        var r = revisions.findByIdAndNoteId(revisionId, note.id).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
        return new ReadingApi.Revision(r.id, r.revision, r.content,
                mapper.treeToValue(r.sourcesJson, ReadingApi.NoteSources.class).sources(), r.sourceResultId, r.createdAt);
    }

    private void publish(ReadingTask task, ReadingState state) {
        var scope = scope(task);
        var completion = state.completion(scope);
        var used = state.findings.stream().flatMap(f -> f.supports().stream()).map(Support::citationId)
                .collect(java.util.stream.Collectors.toSet());
        var result = new ReadingResult(); result.id = UUID.randomUUID().toString(); result.taskId = task.id;
        result.roundNo = task.roundNo; result.completion = completion; result.createdAt = Instant.now();
        var body = new ReadingApi.Result(result.id, task.id, task.roundNo, completion,
                ReadingRendering.render(task.goal, state, scope), List.copyOf(state.findings),
                state.sources.stream().filter(s -> used.contains(s.citationId())).toList(), scope, List.copyOf(state.goals),
                ReadingRendering.gaps(state, scope), ReadingApi.coverage(scope, state), ReadingApi.usage(state), result.createdAt);
        result.payloadJson = json(body); results.save(result);
        task.latestResultId = result.id; task.completion = completion;
        var note = notes.lock(task.id).orElseThrow();
        if (note.version == 0 && note.content.isEmpty() && !state.findings.isEmpty()) {
            archive(note); note.content = body.markdown(); note.adoptedResultId = result.id;
            note.sourcesJson = json(new ReadingApi.NoteSources(body.sources())); notes.saveAndFlush(note);
        }
    }
    private void archive(ReadingNote note) {
        var revision = new ReadingNoteRevision(); revision.id = UUID.randomUUID().toString(); revision.noteId = note.id;
        revision.revision = note.version; revision.content = note.content; revision.sourceResultId = note.adoptedResultId;
        revision.sourcesJson = note.sourcesJson;
        revision.createdAt = Instant.now(); revisions.save(revision);
    }
    private ReadingTask owned(Run run) {
        var task = tasks.lock(run.id(), run.knowledgeBaseId()).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
        if (!task.status.equals("RUNNING") || !Objects.equals(task.runToken, run.token())
                || task.leaseUntil == null || task.leaseUntil.isBefore(Instant.now()))
            throw new BusinessException(ReadingError.CONFLICT);
        return task;
    }
    private ReadingTask require(String id, long kb) {
        return tasks.findByIdAndKnowledgeBaseId(id, kb).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
    }
    private ReadingState state(ReadingTask task) { return mapper.treeToValue(task.checkpointJson, ReadingState.class); }
    // Match JSON parsed from the database. Small LongNode/IntNode differences otherwise
    // trigger phantom updates at every flush, including after a version DTO was returned.
    private JsonNode json(Object value) { return mapper.readTree(mapper.writeValueAsString(value)); }
    private List<DocumentRef> scope(ReadingTask task) { return mapper.treeToValue(task.scopeJson, ReadingApi.Scope.class).documents(); }
    private Run run(ReadingTask task) { return new Run(task.id, task.knowledgeBaseId, task.goal, task.roundNo,
            task.runToken, task.deadlineAt, scope(task), state(task)); }
    private ReadingApi.Task view(ReadingTask task) {
        var state = state(task); var scope = scope(task);
        return new ReadingApi.Task(task.id, task.knowledgeBaseId, task.sessionId, task.parentTaskId, task.goal,
                task.status, task.completion, task.roundNo, task.version, task.latestResultId, scope,
                List.copyOf(state.goals), ReadingApi.coverage(scope, state), List.copyOf(state.stages), ReadingApi.usage(state),
                state.comprehensive, task.errorMessage, task.deadlineAt, task.createdAt, task.updatedAt);
    }
    private ReadingApi.Note noteView(ReadingTask task, ReadingNote note) {
        String pending = Objects.equals(note.adoptedResultId, task.latestResultId) ? null : task.latestResultId;
        return new ReadingApi.Note(note.id, task.id, note.content, note.version, note.adoptedResultId, pending,
                mapper.treeToValue(note.sourcesJson, ReadingApi.NoteSources.class).sources(), "UNCHECKED", note.updatedAt);
    }

    @Transactional
    public ReadingApi.Task expand(String id, long kb, long expectedVersion, List<DocumentRef> scope) {
        var task = tasks.lock(id, kb).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
        if (task.version != expectedVersion || List.of("RUNNING", "PENDING").contains(task.status)
                || !ReadingSources.scopeIds(scope).containsAll(ReadingSources.scopeIds(scope(task))))
            throw new BusinessException(ReadingError.CONFLICT);
        var state = state(task); state.invalidateChanged(scope(task), scope); state.newRound(); state.pendingDraft = null;
        state.pendingSourceIds.clear(); state.pendingReadSourceIds.clear(); state.pendingDocumentId = null; state.pendingCursor = null;
        state.goals = state.goals.stream().map(g -> new Goal(g.id(), g.description(), g.query(), g.engine(), false))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        state.gaps.clear(); state.stage("PLAN_EXECUTE_REFLECT", "scope_expanded", "SUCCEEDED", "用户已确认扩大资料范围，开启新一轮分析");
        task.scopeJson = json(new ReadingApi.Scope(scope)); task.checkpointJson = json(state);
        task.roundNo++; task.status = "PENDING"; task.completion = state.completion(scope); task.deadlineAt = null;
        task.runToken = null; task.leaseUntil = null; task.errorMessage = null;
        tasks.saveAndFlush(task); return view(task);
    }

    @Transactional
    public ReadingApi.Task refresh(String id, long kb, long expectedVersion, int expectedRoundNo, List<DocumentRef> current) {
        var task = tasks.lock(id, kb).orElseThrow(() -> new BusinessException(ReadingError.NOT_FOUND));
        var previous = scope(task);
        if (task.version != expectedVersion || task.roundNo != expectedRoundNo || List.of("RUNNING", "PENDING").contains(task.status)
                || !ReadingSources.scopeIds(current).equals(ReadingSources.scopeIds(previous)) || current.equals(previous))
            throw new BusinessException(ReadingError.CONFLICT);
        var state = state(task); state.invalidateChanged(previous, current); state.newRound();
        state.stage("PLAN_EXECUTE_REFLECT", "source_recheck", "SUCCEEDED", "用户已确认基于当前资料重新分析，历史结果与笔记保留");
        task.scopeJson = json(new ReadingApi.Scope(current)); task.checkpointJson = json(state);
        task.roundNo++; task.status = "PENDING"; task.completion = state.completion(current); task.deadlineAt = null;
        task.runToken = null; task.leaseUntil = null; task.errorMessage = null;
        tasks.saveAndFlush(task); return view(task);
    }
}
