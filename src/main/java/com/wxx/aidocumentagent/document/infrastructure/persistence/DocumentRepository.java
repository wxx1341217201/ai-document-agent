package com.wxx.aidocumentagent.document.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Page<RecentSummary> findAllProjectedBy(Pageable pageable);

    Optional<Document> findByKnowledgeBaseIdAndSha256(Long knowledgeBaseId, String sha256);

    Page<Document> findByKnowledgeBaseId(Long knowledgeBaseId, Pageable pageable);

    /** 工作台只读取最近五份元数据；相同更新时间按 ID 稳定排序。 */
    List<Document> findTop5ByOrderByUpdatedAtDescIdDesc();

    Optional<Document> findByIdAndKnowledgeBaseId(Long id, Long knowledgeBaseId);

    /** 通过知识库边界批量回填引用所需的真实文档元数据，禁止仅按文档 ID 查询。 */
    List<Document> findByKnowledgeBaseIdAndIdIn(long knowledgeBaseId, Collection<Long> documentIds);

    boolean existsByIdAndKnowledgeBaseId(Long id, Long knowledgeBaseId);

    boolean existsByKnowledgeBaseId(Long knowledgeBaseId);

    /** 不读取存储路径、全文、校验值或错误详情。 */
    interface RecentSummary {
        Long getId();
        Long getKnowledgeBaseId();
        String getOriginalName();
        String getExtension();
        DocumentStatus getStatus();
        LocalDateTime getUpdatedAt();
    }
}
