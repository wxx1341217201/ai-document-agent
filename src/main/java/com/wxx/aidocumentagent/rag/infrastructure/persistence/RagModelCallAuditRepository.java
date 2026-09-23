package com.wxx.aidocumentagent.rag.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RagModelCallAuditRepository extends JpaRepository<RagModelCallAudit, Long> {
}
