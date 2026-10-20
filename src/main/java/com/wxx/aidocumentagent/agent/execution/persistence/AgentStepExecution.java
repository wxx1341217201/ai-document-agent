package com.wxx.aidocumentagent.agent.execution.persistence;

import java.time.LocalDateTime;
import com.wxx.aidocumentagent.agent.AgentStepSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "agent_step_execution")
public class AgentStepExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "execution_id", nullable = false, length = 36, updatable = false) private String executionId;
    @Column(name = "knowledge_base_id", nullable = false, updatable = false) private long knowledgeBaseId;
    @Column(name = "step_id", nullable = false, length = 64, updatable = false) private String stepId;
    @Column(nullable = false, length = 32) private String kind;
    @Column(nullable = false, length = 64) private String operation;
    @Column(nullable = false, length = 16) private String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "depends_on_json", columnDefinition = "json")
    private JsonNode dependsOnJson;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Version @Column(nullable = false) private long version;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected AgentStepExecution() { }

    public static AgentStepExecution agentRun(String executionId, long knowledgeBaseId) {
        AgentStepExecution step = new AgentStepExecution();
        step.executionId = executionId;
        step.knowledgeBaseId = knowledgeBaseId;
        step.stepId = "agent_run";
        step.kind = "AGENT";
        step.operation = "execute";
        step.status = "PENDING";
        return step;
    }

    public static AgentStepExecution from(String executionId, long knowledgeBaseId, AgentStepSummary summary,
                                          JsonNode dependsOn) {
        AgentStepExecution step = new AgentStepExecution();
        step.executionId = executionId;
        step.knowledgeBaseId = knowledgeBaseId;
        step.stepId = summary.stepId();
        step.apply(summary, dependsOn);
        return step;
    }

    public void apply(AgentStepSummary summary, JsonNode dependsOn) {
        kind = summary.kind().name();
        operation = summary.operation();
        status = summary.status().name();
        dependsOnJson = dependsOn;
        errorCode = summary.errorCode() == null ? null : summary.errorCode().name();
    }

    public void markRunning() { status = "RUNNING"; errorCode = null; }
    public void markSucceeded() { status = "SUCCEEDED"; errorCode = null; }
    public void markFailed(String code) { status = "FAILED"; errorCode = code; }

    @PrePersist void created() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void updated() { updatedAt = LocalDateTime.now(); }

    public String getStepId() { return stepId; }
    public String getKind() { return kind; }
    public String getOperation() { return operation; }
    public String getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public JsonNode getDependsOnJson() { return dependsOnJson; }
}
