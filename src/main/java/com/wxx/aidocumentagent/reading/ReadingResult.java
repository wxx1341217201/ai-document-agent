package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity @Table(name = "reading_result")
public class ReadingResult {
    @Id @Column(length = 36) String id;
    @Column(name = "task_id", nullable = false, length = 36) String taskId;
    @Column(name = "round_no", nullable = false) int roundNo;
    @Column(nullable = false, length = 16) String completion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload_json", nullable = false, columnDefinition = "json") JsonNode payloadJson;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    protected ReadingResult() { }
}
