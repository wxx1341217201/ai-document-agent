package com.wxx.aidocumentagent.knowledgebase.application;

import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.document.infrastructure.persistence.DocumentRepository;
import com.wxx.aidocumentagent.knowledgebase.api.dto.CreateKnowledgeBaseRequest;
import com.wxx.aidocumentagent.knowledgebase.api.dto.KnowledgeBasePageResponse;
import com.wxx.aidocumentagent.knowledgebase.api.dto.KnowledgeBaseResponse;
import com.wxx.aidocumentagent.knowledgebase.api.dto.UpdateKnowledgeBaseRequest;
import com.wxx.aidocumentagent.knowledgebase.domain.KnowledgeBaseErrorCode;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBase;
import com.wxx.aidocumentagent.knowledgebase.infrastructure.persistence.KnowledgeBaseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库租户边界的应用服务。
 */
@Service
public class KnowledgeBaseApplicationService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;

    public KnowledgeBaseApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                           DocumentRepository documentRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        rejectDuplicateName(request.name());
        KnowledgeBase knowledgeBase = KnowledgeBase.create(request.name(), request.description());
        try {
            return toResponse(knowledgeBaseRepository.saveAndFlush(knowledgeBase));
        }
        catch (DataIntegrityViolationException exception) {
            throw nameConflict(exception);
        }
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getById(long id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public KnowledgeBasePageResponse list(int page, int size) {
        Page<KnowledgeBase> result = knowledgeBaseRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
        List<KnowledgeBaseResponse> content = result.getContent().stream().map(this::toResponse).toList();
        return new KnowledgeBasePageResponse(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional
    public KnowledgeBaseResponse update(long id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = findById(id);
        if (!knowledgeBase.getName().equals(request.name())
                && knowledgeBaseRepository.existsByNameAndIdNot(request.name(), id)) {
            throw nameConflict(null);
        }

        knowledgeBase.update(request.name(), request.description());
        try {
            knowledgeBaseRepository.flush();
            return toResponse(knowledgeBase);
        }
        catch (DataIntegrityViolationException exception) {
            throw nameConflict(exception);
        }
    }

    @Transactional
    public void delete(long id) {
        KnowledgeBase knowledgeBase = findById(id);
        if (documentRepository.existsByKnowledgeBaseId(id)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_EMPTY);
        }
        knowledgeBaseRepository.delete(knowledgeBase);
    }

    private void rejectDuplicateName(String name) {
        if (knowledgeBaseRepository.existsByName(name)) {
            throw nameConflict(null);
        }
    }

    private KnowledgeBase findById(long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    private BusinessException nameConflict(Throwable cause) {
        return cause == null
                ? new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT)
                : new BusinessException(KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT,
                KnowledgeBaseErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT.defaultMessage(), cause);
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(knowledgeBase.getId(), knowledgeBase.getName(), knowledgeBase.getDescription(),
                knowledgeBase.getCreatedAt(), knowledgeBase.getUpdatedAt());
    }
}
