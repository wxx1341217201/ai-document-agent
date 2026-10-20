package com.wxx.aidocumentagent.dashboard.application;

import com.wxx.aidocumentagent.dashboard.api.dto.DashboardPageResponse;
import com.wxx.aidocumentagent.dashboard.api.dto.RecentDocumentResponse;
import com.wxx.aidocumentagent.dashboard.api.dto.RecentKnowledgeBaseResponse;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按更新时间与 ID 稳定倒序，只查询当前页所需的摘要字段。 */
@Service
@Transactional(readOnly = true)
public class DashboardApplicationService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;

    public DashboardApplicationService(KnowledgeBaseRepository knowledgeBases, DocumentRepository documents) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
    }

    public DashboardPageResponse<RecentKnowledgeBaseResponse> knowledgeBases(int page, int size) {
        return response(knowledgeBases.findAllProjectedBy(recentPage(page, size))
                .map(kb -> new RecentKnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(),
                        kb.getUpdatedAt())));
    }

    public DashboardPageResponse<RecentDocumentResponse> documents(int page, int size) {
        return response(documents.findAllProjectedBy(recentPage(page, size))
                .map(document -> new RecentDocumentResponse(document.getId(), document.getKnowledgeBaseId(),
                        document.getOriginalName(), document.getExtension(), document.getStatus(),
                        document.getUpdatedAt())));
    }

    private PageRequest recentPage(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
    }

    private <T> DashboardPageResponse<T> response(Page<T> page) {
        return new DashboardPageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
