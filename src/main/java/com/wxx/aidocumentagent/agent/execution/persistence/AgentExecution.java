package com.wxx.aidocumentagent.agent.execution.persistence;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import com.wxx.aidocumentagent.agent.AgentMode;
import com.wxx.aidocumentagent.agent.execution.ExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "agent_execution")
public class AgentExecution {
    @Id @Column(name = "execution_id", length = 36)
    private String executionId;
    @Column(name = "request_key", nullable = false, length = 128, updatable = false)
    private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;
    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private long knowledgeBaseId;
    @Column(name = "conversation_id", updatable = false)
    private Long conversationId;
    @Enumerated(EnumType.STRING) @Column(name = "agent_mode", nullable = false, length = 32, updatable = false)
    private AgentMode agentMode;
    @Column(nullable = false, length = 2000, updatable = false)
    private String question;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "parameters_json", nullable = false, columnDefinition = "json", updatable = false)
    private JsonNode parametersJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ExecutionStatus status;
    @Column(name = "current_step", length = 64)
    private String currentStep;
    @Version @Column(nullable = false)
    private long version;
    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;
    @Column(name = "run_token", length = 36)
    private String runToken;
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;
    @Column(nullable = false)
    private boolean retryable;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "result_json", columnDefinition = "json")
    private JsonNode resultJson;
    @Column(name = "error_code", length = 64)
    private String errorCode;
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AgentExecution() { }

    public static AgentExecution create(String key, String hash, long knowledgeBaseId, Long conversationId,
                                        AgentMode mode, String question, JsonNode parameters, LocalDateTime deadline) {
        AgentExecution entity = new AgentExecution();
        entity.executionId = UUID.randomUUID().toString();
        entity.requestKey = Objects.requireNonNull(key);
        entity.requestHash = Objects.requireNonNull(hash);
        entity.knowledgeBaseId = knowledgeBaseId;
        entity.conversationId = conversationId;
        entity.agentMode = Objects.requireNonNull(mode);
        entity.question = Objects.requireNonNull(question);
        entity.parametersJson = Objects.requireNonNull(parameters);
        entity.status = ExecutionStatus.PENDING;
        entity.currentStep = "agent_run";
        entity.deadlineAt = Objects.requireNonNull(deadline);
        return entity;
    }

    @PrePersist void created() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void updated() { updatedAt = LocalDateTime.now(); }

    public synchronized String claim(LocalDateTime now, LocalDateTime until, boolean resume) {
        if (cancelRequested || !(resume ? status == ExecutionStatus.PAUSED
                || (status == ExecutionStatus.FAILED && retryable) : status == ExecutionStatus.PENDING)) {
            throw new IllegalStateException("执行状态不能进入RUNNING");
        }
        if (!now.isBefore(deadlineAt) && resultJson == null) throw new IllegalStateException("执行已过恢复期限");
        status = ExecutionStatus.RUNNING;
        runToken = UUID.randomUUID().toString();
        leaseUntil = until;
        startedAt = now;
        finishedAt = null;
        errorCode = null;
        retryable = false;
        currentStep = resultJson == null ? "agent_run" : "finalize";
        return runToken;
    }

    public boolean ownedBy(String token) {
        return status == ExecutionStatus.RUNNING && Objects.equals(runToken, token);
    }

    public void checkpoint(JsonNode safeResult) {
        requireRunning();
        if (resultJson != null) throw new IllegalStateException("结果检查点已存在");
        resultJson = Objects.requireNonNull(safeResult);
        currentStep = "finalize";
    }

    public void progress(String stepId, LocalDateTime extendedLease) {
        requireRunning();
        currentStep = stepId;
        if (leaseUntil == null || leaseUntil.isBefore(extendedLease)) leaseUntil = extendedLease;
    }

    public void finishSuccess(LocalDateTime now) {
        requireRunning();
        if (resultJson == null) throw new IllegalStateException("结果检查点不存在");
        if (cancelRequested) {
            finishCancelled(now);
            return;
        }
        status = ExecutionStatus.SUCCEEDED;
        clearLease(now);
    }

    public void finishFailure(String code, boolean mayRetry, LocalDateTime now) {
        requireRunning();
        if (cancelRequested || "EXECUTION_CANCELLED".equals(code)) {
            finishCancelled(now);
            return;
        }
        status = ExecutionStatus.FAILED;
        errorCode = code;
        retryable = mayRetry && now.isBefore(deadlineAt);
        clearLease(now);
    }

    public void requestCancel(LocalDateTime now) {
        if (status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.CANCELLED
                || status == ExecutionStatus.FAILED) {
            throw new IllegalStateException("终态执行不能取消");
        }
        cancelRequested = true;
        if (status != ExecutionStatus.RUNNING) finishCancelled(now);
    }

    public void recover(LocalDateTime now) {
        requireRunning();
        if (cancelRequested) {
            finishCancelled(now);
        }
        else if (resultJson != null && !now.isBefore(deadlineAt)) {
            finishSuccess(now);
        }
        else if (!now.isBefore(deadlineAt)) {
            status = ExecutionStatus.FAILED;
            errorCode = "EXECUTION_EXPIRED";
            retryable = false;
            clearLease(now);
        }
        else {
            status = ExecutionStatus.PAUSED;
            errorCode = null;
            retryable = true;
            runToken = null;
            leaseUntil = null;
        }
    }

    public void pauseClaim(LocalDateTime now) {
        requireRunning();
        if (cancelRequested) {
            finishCancelled(now);
            return;
        }
        status = ExecutionStatus.PAUSED;
        retryable = true;
        runToken = null;
        leaseUntil = null;
    }

    public void expireWaiting(LocalDateTime now) {
        if ((status == ExecutionStatus.PENDING || status == ExecutionStatus.PAUSED)
                && !now.isBefore(deadlineAt)) {
            if (cancelRequested) {
                finishCancelled(now);
                return;
            }
            if (status == ExecutionStatus.PAUSED && resultJson != null) {
                status = ExecutionStatus.SUCCEEDED;
                clearLease(now);
                return;
            }
            status = ExecutionStatus.FAILED;
            errorCode = "EXECUTION_EXPIRED";
            retryable = false;
            clearLease(now);
        }
    }

    private void finishCancelled(LocalDateTime now) {
        status = ExecutionStatus.CANCELLED;
        resultJson = null;
        errorCode = "EXECUTION_CANCELLED";
        retryable = false;
        clearLease(now);
    }

    private void clearLease(LocalDateTime now) {
        runToken = null;
        leaseUntil = null;
        currentStep = null;
        finishedAt = now;
    }

    private void requireRunning() {
        if (status != ExecutionStatus.RUNNING) throw new IllegalStateException("执行不在RUNNING状态");
    }

    public String getExecutionId() { return executionId; }
    public String getRequestKey() { return requestKey; }
    public String getRequestHash() { return requestHash; }
    public long getKnowledgeBaseId() { return knowledgeBaseId; }
    public Long getConversationId() { return conversationId; }
    public AgentMode getAgentMode() { return agentMode; }
    public String getQuestion() { return question; }
    public JsonNode getParametersJson() { return parametersJson; }
    public ExecutionStatus getStatus() { return status; }
    public String getCurrentStep() { return currentStep; }
    public long getVersion() { return version; }
    public LocalDateTime getDeadlineAt() { return deadlineAt; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public String getRunToken() { return runToken; }
    public boolean isCancelRequested() { return cancelRequested; }
    public boolean isRetryable() { return retryable; }
    public JsonNode getResultJson() { return resultJson; }
    public String getErrorCode() { return errorCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
