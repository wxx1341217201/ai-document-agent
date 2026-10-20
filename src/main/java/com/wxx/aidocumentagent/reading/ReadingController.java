package com.wxx.aidocumentagent.reading;

import java.nio.charset.StandardCharsets;
import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reading/tasks")
@ConditionalOnProperty(name = {"app.rag.enabled", "app.reading.enabled"}, havingValue = "true", matchIfMissing = true)
public class ReadingController {
    private final ReadingStore store;
    private final ReadingService service;
    public ReadingController(ReadingStore store, ReadingService service) { this.store = store; this.service = service; }
    @PostMapping
    public ResponseEntity<ApiResponse<ReadingApi.Task>> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                               @Valid @RequestBody ReadingApi.Create request) {
        return ResponseEntity.accepted().body(success(service.create(key, request)));
    }
    @GetMapping
    public ApiResponse<ReadingApi.Page<ReadingApi.Summary>> list(@RequestParam @Positive long knowledgeBaseId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 36) String sessionId) {
        return success(store.list(knowledgeBaseId, page, size, sessionId));
    }
    @GetMapping("/{id}")
    public ApiResponse<ReadingApi.Task> get(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId) {
        return success(store.get(id, knowledgeBaseId));
    }
    @PostMapping("/{id}/{action:continue|resume|cancel}")
    public ResponseEntity<ApiResponse<ReadingApi.Task>> action(@PathVariable String id, @PathVariable String action,
            @RequestParam @Positive long knowledgeBaseId, @Valid @RequestBody ReadingApi.Action request) {
        return ResponseEntity.accepted().body(success(store.action(id, knowledgeBaseId, request.expectedVersion(), request.expectedRoundNo(), action)));
    }
    @PostMapping("/{id}/scope")
    public ResponseEntity<ApiResponse<ReadingApi.Task>> expand(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId,
                                                              @Valid @RequestBody ReadingApi.Expand request) {
        return ResponseEntity.accepted().body(success(service.expand(id, knowledgeBaseId, request)));
    }
    @PostMapping("/{id}/refresh-sources")
    public ResponseEntity<ApiResponse<ReadingApi.Task>> refresh(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId,
                                                                @Valid @RequestBody ReadingApi.Action request) {
        return ResponseEntity.accepted().body(success(service.refresh(id, knowledgeBaseId, request)));
    }
    @GetMapping("/{id}/results")
    public ApiResponse<ReadingApi.Page<ReadingApi.ResultSummary>> results(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return success(store.resultHistory(id, knowledgeBaseId, page, size));
    }
    @GetMapping("/{id}/results/{resultId}")
    public ApiResponse<ReadingApi.Result> result(@PathVariable String id, @PathVariable String resultId, @RequestParam @Positive long knowledgeBaseId) {
        return success(store.result(id, knowledgeBaseId, resultId));
    }
    @GetMapping("/{id}/note")
    public ApiResponse<ReadingApi.Note> note(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId) {
        return success(service.note(id, knowledgeBaseId));
    }
    @PutMapping("/{id}/note")
    public ApiResponse<ReadingApi.Note> edit(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId,
                                            @Valid @RequestBody ReadingApi.Edit request) {
        return success(store.edit(id, knowledgeBaseId, request));
    }
    @GetMapping("/{id}/note/revisions")
    public ApiResponse<ReadingApi.Page<ReadingApi.RevisionSummary>> revisions(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return success(store.noteHistory(id, knowledgeBaseId, page, size));
    }
    @GetMapping("/{id}/note/revisions/{revisionId}")
    public ApiResponse<ReadingApi.Revision> revision(@PathVariable String id, @PathVariable String revisionId, @RequestParam @Positive long knowledgeBaseId) {
        return success(store.revision(id, knowledgeBaseId, revisionId));
    }
    @GetMapping("/{id}/note/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam @Positive long knowledgeBaseId) {
        var note = store.note(id, knowledgeBaseId);
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("reading-note.md").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
                .body(ReadingRendering.export(note).getBytes(StandardCharsets.UTF_8));
    }
    private <T> ApiResponse<T> success(T value) { return ApiResponse.success(value, TraceIdFilter.currentTraceId()); }
}
