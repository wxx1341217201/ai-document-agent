package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;
import com.wxx.aidocumentagent.reading.ReadingState.*;

public final class ReadingApi {
    private ReadingApi() { }
    public record Create(@Positive long knowledgeBaseId,
                         @NotBlank @Size(max = 2000) String goal,
                         @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> documentIds,
                         boolean comprehensive, @Size(max = 36) String parentTaskId) { }
    public record Action(@PositiveOrZero long expectedVersion, @Min(1) int expectedRoundNo) { }
    public record Expand(@NotEmpty @Size(max = 20) List<@NotNull @Positive Long> documentIds,
                         @PositiveOrZero long expectedVersion) { }
    public record Edit(@NotNull @Size(max = 120000) String content, @PositiveOrZero long expectedVersion,
                       @Size(max = 36) String sourceResultId) { }
    public record Scope(List<DocumentRef> documents) { public Scope { documents = List.copyOf(documents); } }
    public record NoteSources(List<Source> sources) { public NoteSources { sources = List.copyOf(sources); } }
    public record Coverage(long documentId, String name, long reviewedChunks, long totalChunks) { }
    public record Usage(int modelCalls, int toolCalls, int replans, long promptTokens, long completionTokens) { }
    public record Task(String id, long knowledgeBaseId, String sessionId, String parentTaskId, String goal,
                       String status, String completion, int roundNo, long version, String latestResultId,
                       List<DocumentRef> documents, List<Goal> goals, List<Coverage> coverage, List<Stage> stages,
                       Usage usage, boolean comprehensive, String errorMessage, Instant deadlineAt,
                       Instant createdAt, Instant updatedAt) { }
    public record Summary(String id, long knowledgeBaseId, String sessionId, String goal, String status,
                          String completion, int roundNo, String latestResultId, Instant createdAt, Instant updatedAt) { }
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) { }
    public record Result(String id, String taskId, int roundNo, String completion, String markdown,
                         List<Finding> findings, List<Source> sources, List<DocumentRef> documents, List<Goal> goals,
                         List<String> gaps, List<Coverage> coverage, Usage usage, Instant createdAt) { }
    public record ResultSummary(String id, int roundNo, String completion, Instant createdAt) { }
    public record Note(String id, String taskId, String content, long version, String adoptedResultId,
                       String pendingResultId, List<Source> sources, String sourceStatus, Instant updatedAt) { }
    public record Revision(String id, long revision, String content, List<Source> sources, String sourceResultId, Instant createdAt) { }
    public record RevisionSummary(String id, long revision, String sourceResultId, Instant createdAt) { }

    static Usage usage(ReadingState state) {
        return new Usage(state.modelCalls, state.toolCalls, state.replans, state.promptTokens, state.completionTokens);
    }
    static List<Coverage> coverage(List<DocumentRef> docs, ReadingState state) {
        return docs.stream().map(d -> new Coverage(d.id(), d.name(),
                state.cursors.getOrDefault(d.id(), Cursor.start()).reviewedChunks(), d.chunks())).toList();
    }
}
