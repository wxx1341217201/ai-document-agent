package com.wxx.aidocumentagent.dashboard.application;

import java.time.LocalDateTime;
import java.util.List;

import com.wxx.aidocumentagent.document.domain.DocumentStatus;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardApplicationServiceTest {

    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DashboardApplicationService service = new DashboardApplicationService(knowledgeBases, documents);

    @Test
    void 文档页保留总量并以更新时间和ID倒序查询指定页() {
        var page = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        var summary = mock(DocumentRepository.RecentSummary.class);
        when(summary.getId()).thenReturn(1846L);
        when(summary.getKnowledgeBaseId()).thenReturn(10L);
        when(summary.getOriginalName()).thenReturn("paper.txt");
        when(summary.getExtension()).thenReturn("txt");
        when(summary.getStatus()).thenReturn(DocumentStatus.READY);
        when(summary.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 10, 19, 12, 0));
        when(documents.findAllProjectedBy(page)).thenReturn(new PageImpl<>(List.of(summary), page, 1844));

        var result = service.documents(1, 5);

        assertThat(result.content()).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo(1846);
            assertThat(document.originalName()).isEqualTo("paper.txt");
            assertThat(document.status()).isEqualTo(DocumentStatus.READY);
        });
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalElements()).isEqualTo(1844);
        assertThat(result.totalPages()).isEqualTo(369);
        verify(documents).findAllProjectedBy(page);
        verifyNoInteractions(knowledgeBases);
    }

    @Test
    void 知识库空页保留正确的分页协议且不加载文档() {
        var page = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        when(knowledgeBases.findAllProjectedBy(page)).thenReturn(new PageImpl<>(List.of(), page, 0));

        var result = service.knowledgeBases(0, 5);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        verify(knowledgeBases).findAllProjectedBy(page);
        verifyNoInteractions(documents);
    }
}
