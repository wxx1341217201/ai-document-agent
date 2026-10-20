package com.wxx.aidocumentagent.recovery.benchmark;

import java.util.Map;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@ConditionalOnProperty(name = "app.benchmark.recovery.node", havingValue = "true")
@RequestMapping("/benchmark/recovery")
public class RecoveryFixtureController {
    private final RecoveryFixtureService service;
    public RecoveryFixtureController(RecoveryFixtureService service) { this.service = service; }
    @GetMapping("/snapshot/{id}")
    public RecoveryBenchmarkObservation.Snapshot snapshot(@RequestHeader("Authorization") String auth,
            @PathVariable @Positive long id, @RequestParam @Positive long knowledgeBaseId) {
        service.authorize(auth); return service.snapshot(knowledgeBaseId, id);
    }
    @GetMapping("/cache/{id}")
    public Map<String, Object> cacheState(@RequestHeader("Authorization") String auth,
            @PathVariable @Positive long id, @RequestParam @Positive long knowledgeBaseId) {
        service.authorize(auth); return service.cache(knowledgeBaseId, id, "STATE");
    }
    @PostMapping("/cache/{id}/{operation}")
    public Map<String, Object> cacheFault(@RequestHeader("Authorization") String auth,
            @PathVariable @Positive long id, @RequestParam @Positive long knowledgeBaseId, @PathVariable String operation) {
        service.authorize(auth); return service.cache(knowledgeBaseId, id, operation);
    }
    @PostMapping("/execution/{id}/interrupt")
    public Map<String, Object> interrupt(@RequestHeader("Authorization") String auth, @PathVariable String id,
            @RequestParam @Positive long knowledgeBaseId) {
        service.authorize(auth); return service.interrupt(knowledgeBaseId, id);
    }
    @GetMapping("/execution/{id}/attempts")
    public Map<String, Object> attempts(@RequestHeader("Authorization") String auth, @PathVariable String id,
            @RequestParam @Positive long knowledgeBaseId) {
        service.authorize(auth); return service.attempts(knowledgeBaseId, id);
    }
}
