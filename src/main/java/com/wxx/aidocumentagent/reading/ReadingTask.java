package com.wxx.aidocumentagent.reading;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity @Table(name = "reading_task")
public class ReadingTask {
    @Id @Column(length = 36) String id;
    @Column(name = "knowledge_base_id", nullable = false) long knowledgeBaseId;
    @Column(name = "session_id", nullable = false, length = 36) String sessionId;
    @Column(name = "parent_task_id", length = 36) String parentTaskId;
    @Column(name = "request_key", nullable = false, length = 128) String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64) String requestHash;
    @Column(nullable = false, length = 2000) String goal;
    @Column(nullable = false, length = 16) String status;
    @Column(nullable = false, length = 16) String completion = "NONE";
    @Column(name = "round_no", nullable = false) int roundNo = 1;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "scope_json", nullable = false, columnDefinition = "json") JsonNode scopeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "checkpoint_json", nullable = false, columnDefinition = "json") JsonNode checkpointJson;
    @Column(name = "deadline_at") Instant deadlineAt;
    @Column(name = "lease_until") Instant leaseUntil;
    @Column(name = "run_token", length = 36) String runToken;
    @Column(name = "latest_result_id", length = 36) String latestResultId;
    @Column(name = "error_message", length = 256) String errorMessage;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected ReadingTask() { }
    @PrePersist void createTime() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void updateTime() { updatedAt = Instant.now(); }
}
