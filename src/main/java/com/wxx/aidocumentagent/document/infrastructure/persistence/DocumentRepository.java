package com.wxx.aidocumentagent.document.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByKnowledgeBaseIdAndSha256(Long knowledgeBaseId, String sha256);

    Page<Document> findByKnowledgeBaseId(Long knowledgeBaseId, Pageable pageable);

    Optional<Document> findByIdAndKnowledgeBaseId(Long id, Long knowledgeBaseId);

    boolean existsByKnowledgeBaseId(Long knowledgeBaseId);
}
