package com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Page<RecentSummary> findAllProjectedBy(Pageable pageable);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /** 闭合投影使工作台查询仅选择列表需要的列。 */
    interface RecentSummary {
        Long getId();
        String getName();
        String getDescription();
        LocalDateTime getUpdatedAt();
    }
}
