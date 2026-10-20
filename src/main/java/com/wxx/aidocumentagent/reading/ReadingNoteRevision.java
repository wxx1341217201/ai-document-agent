package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity @Table(name = "reading_note_revision")
public class ReadingNoteRevision {
    @Id @Column(length = 36) String id;
    @Column(name = "note_id", nullable = false, length = 36) String noteId;
    @Column(nullable = false) long revision;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false, columnDefinition = "MEDIUMTEXT") String content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "sources_json", nullable = false, columnDefinition = "json") JsonNode sourcesJson;
    @Column(name = "source_result_id", length = 36) String sourceResultId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    protected ReadingNoteRevision() { }
}
