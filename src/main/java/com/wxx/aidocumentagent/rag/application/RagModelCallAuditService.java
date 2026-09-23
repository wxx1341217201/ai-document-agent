package com.wxx.aidocumentagent.rag.application;

import java.time.Duration;
import java.util.Objects;

import com.wxx.aidocumentagent.rag.infrastructure.persistence.RagModelCallAudit;
import com.wxx.aidocumentagent.rag.infrastructure.persistence.RagModelCallAuditRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 以独立事务保存模型调用审计，使问答读取事务不会影响审计成功落库。 */
public class RagModelCallAuditService {

    private final RagModelCallAuditRepository repository;

    public RagModelCallAuditService(RagModelCallAuditRepository repository) {
        this.repository = Objects.requireNonNull(repository, "ragModelCallAuditRepository不能为空");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(long knowledgeBaseId, String traceId, RagChatCompletion completion, Duration elapsed) {
        repository.save(RagModelCallAudit.success(knowledgeBaseId, traceId, completion, elapsed));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long knowledgeBaseId, String traceId, String modelName, RagChatCompletion completion,
                              Duration elapsed, String errorCode) {
        repository.save(RagModelCallAudit.failure(knowledgeBaseId, traceId, modelName, completion, elapsed,
                errorCode));
    }
}
