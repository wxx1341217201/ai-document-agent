package com.wxx.aidocumentagent.ingestion.benchmark;

import java.util.Map;

import com.wxx.aidocumentagent.document.application.UploadDocumentCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/benchmark")
@ConditionalOnProperty(name = "app.benchmark.rabbitmq.node", havingValue = "true")
public class RabbitBenchmarkController {
    private final RabbitBenchmarkApplicationService benchmark;

    public RabbitBenchmarkController(RabbitBenchmarkApplicationService benchmark) {
        this.benchmark = benchmark;
    }

    @PostMapping("/begin")
    public Map<String, Object> begin(@RequestParam String phase) {
        return benchmark.begin(phase);
    }

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    public Map<String, Object> submit(@RequestPart("file") MultipartFile file,
            @RequestParam String corpusId, @RequestParam int round, HttpServletRequest request)
            throws InterruptedException {
        var command = new UploadDocumentCommand(file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file::getInputStream);
        var result = benchmark.submit(command, corpusId, round, (double) request.getAttribute("m19e.arrived"));
        request.setAttribute("m19e.documentId", result.documentId());
        return Map.of("documentId", result.documentId(), "knowledgeBaseId", result.knowledgeBaseId());
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> status(@PathVariable long id) {
        return benchmark.status(id);
    }

    @GetMapping("/sample")
    public Map<String, Object> sample() {
        return benchmark.sample();
    }

    @GetMapping("/export")
    public Map<String, Object> export() {
        return benchmark.export();
    }
}
