package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity @Table(name = "reading_note")
public class ReadingNote {
    @Id @Column(length = 36) String id;
    @Column(name = "task_id", nullable = false, length = 36) String taskId;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false, columnDefinition = "MEDIUMTEXT") String content = "";
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "sources_json", nullable = false, columnDefinition = "json") JsonNode sourcesJson;
    @Column(name = "adopted_result_id", length = 36) String adoptedResultId;
    @Version @Column(nullable = false) long version;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected ReadingNote() { }
    @PrePersist @PreUpdate void time() { updatedAt = Instant.now(); }
}
