package com.wxx.aidocumentagent.knowledgebase.application;

import java.time.LocalDateTime;
import java.util.Optional;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.api.dto.CreateKnowledgeBaseRequest;
import com.wxx.aidocumentagent.knowledgebase.api.dto.KnowledgeBaseResponse;
import com.wxx.aidocumentagent.knowledgebase.api.dto.UpdateKnowledgeBaseRequest;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBase;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseApplicationServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private DocumentRepository documentRepository;

    private KnowledgeBaseApplicationService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseApplicationService(knowledgeBaseRepository, documentRepository);
    }

    @Test
    void 创建名称标准化后的知识库() {
        when(knowledgeBaseRepository.existsByName("产品文档")).thenReturn(false);
        when(knowledgeBaseRepository.saveAndFlush(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase knowledgeBase = invocation.getArgument(0);
            ReflectionTestUtils.setField(knowledgeBase, "id", 7L);
            ReflectionTestUtils.setField(knowledgeBase, "createdAt", LocalDateTime.of(2026, 9, 20, 10, 0));
            ReflectionTestUtils.setField(knowledgeBase, "updatedAt", LocalDateTime.of(2026, 9, 20, 10, 0));
            return knowledgeBase;
        });

        KnowledgeBaseResponse response = service.create(new CreateKnowledgeBaseRequest("  产品文档  ", "内部资料"));

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("产品文档");
        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.name()).isEqualTo("产品文档");
    }

    @Test
    void 已存在名称返回稳定冲突错误码() {
        when(knowledgeBaseRepository.existsByName("产品文档")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateKnowledgeBaseRequest("产品文档", null)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT));

        verify(knowledgeBaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void 未知知识库返回未找到错误() {
        when(knowledgeBaseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    @Test
    void 更新为其他知识库名称时拒绝请求() {
        KnowledgeBase knowledgeBase = existingKnowledgeBase(7L, "当前名称");
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.existsByNameAndIdNot("已有名称", 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, new UpdateKnowledgeBaseRequest("已有名称", "说明")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT));

        verify(knowledgeBaseRepository, never()).flush();
    }

    @Test
    void 删除存在的空知识库() {
        KnowledgeBase knowledgeBase = existingKnowledgeBase(7L, "可删除知识库");
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));

        service.delete(7L);

        verify(knowledgeBaseRepository).delete(eq(knowledgeBase));
    }

    @Test
    void 知识库仍包含文档时拒绝删除() {
        KnowledgeBase knowledgeBase = existingKnowledgeBase(7L, "有文档知识库");
        when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(knowledgeBase));
        when(documentRepository.existsByKnowledgeBaseId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_EMPTY));

        verify(knowledgeBaseRepository, never()).delete(any());
    }

    private KnowledgeBase existingKnowledgeBase(long id, String name) {
        KnowledgeBase knowledgeBase = KnowledgeBase.create(name, "说明");
        ReflectionTestUtils.setField(knowledgeBase, "id", id);
        ReflectionTestUtils.setField(knowledgeBase, "createdAt", LocalDateTime.of(2026, 9, 20, 10, 0));
        ReflectionTestUtils.setField(knowledgeBase, "updatedAt", LocalDateTime.of(2026, 9, 20, 10, 0));
        return knowledgeBase;
    }
}
