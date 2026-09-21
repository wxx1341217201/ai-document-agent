package com.wxx.aidocumentagent.document.api;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.document.api.dto.DocumentPageResponse;
import com.wxx.aidocumentagent.document.api.dto.DocumentResponse;
import com.wxx.aidocumentagent.document.application.DocumentApplicationService;
import com.wxx.aidocumentagent.document.application.UploadDocumentCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档上传和元数据操作的 HTTP 协议适配器。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentApplicationService documentApplicationService;

    public DocumentController(DocumentApplicationService documentApplicationService) {
        this.documentApplicationService = documentApplicationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(@PathVariable @Positive long knowledgeBaseId,
                                                                  @RequestPart("file") MultipartFile file) {
        UploadDocumentCommand command = new UploadDocumentCommand(file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file::getInputStream);
        DocumentResponse response = documentApplicationService.upload(knowledgeBaseId, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(success(response));
    }

    @GetMapping
    public ApiResponse<DocumentPageResponse> list(@PathVariable @Positive long knowledgeBaseId,
                                                   @RequestParam(defaultValue = "0") @Min(value = 0, message = "页码不能小于0")
                                                   int page,
                                                   @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页数量不能小于1")
                                                   @Max(value = MAX_PAGE_SIZE, message = "每页数量不能超过100") int size) {
        return success(documentApplicationService.list(knowledgeBaseId, page, size));
    }

    @GetMapping("/{documentId}")
    public ApiResponse<DocumentResponse> getById(@PathVariable @Positive long knowledgeBaseId,
                                                  @PathVariable @Positive long documentId) {
        return success(documentApplicationService.getById(knowledgeBaseId, documentId));
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> delete(@PathVariable @Positive long knowledgeBaseId,
                                    @PathVariable @Positive long documentId) {
        documentApplicationService.delete(knowledgeBaseId, documentId);
        return success(null);
    }

    private <T> ApiResponse<T> success(T data) {
        return ApiResponse.success(data, TraceIdFilter.currentTraceId());
    }
}
