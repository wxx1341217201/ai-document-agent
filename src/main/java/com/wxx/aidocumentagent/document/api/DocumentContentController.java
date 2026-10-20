package com.wxx.aidocumentagent.document.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.document.api.dto.DocumentChunkPageResponse;
import com.wxx.aidocumentagent.document.application.DocumentContentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
public class DocumentContentController {
    private final DocumentContentService content;
    public DocumentContentController(DocumentContentService content) { this.content = content; }

    @GetMapping("/chunks")
    public ApiResponse<DocumentChunkPageResponse> chunks(@PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(content.chunks(knowledgeBaseId, documentId, page, size),
                TraceIdFilter.currentTraceId());
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@PathVariable @Positive long knowledgeBaseId,
                                             @PathVariable @Positive long documentId) {
        var file = content.download(knowledgeBaseId, documentId);
        var filename = file.originalName().replace('\r', '_').replace('\n', '_');
        return ResponseEntity.ok().contentType(mediaType(file.extension())).contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(file.content()));
    }

    private MediaType mediaType(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "txt" -> MediaType.TEXT_PLAIN;
            case "docx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
