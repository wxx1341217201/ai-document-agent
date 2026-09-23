package com.wxx.aidocumentagent.document.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByKnowledgeBaseIdAndSha256(Long knowledgeBaseId, String sha256);

    Page<Document> findByKnowledgeBaseId(Long knowledgeBaseId, Pageable pageable);

    Optional<Document> findByIdAndKnowledgeBaseId(Long id, Long knowledgeBaseId);

    /** 通过知识库边界批量回填引用所需的真实文档元数据，禁止仅按文档 ID 查询。 */
    List<Document> findByKnowledgeBaseIdAndIdIn(long knowledgeBaseId, Collection<Long> documentIds);

    boolean existsByKnowledgeBaseId(Long knowledgeBaseId);
}
